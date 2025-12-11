package Server.control;

import common.MachineInfo;
import common.MachineInfo.MachineStatus;
import common.Component;
import org.omg.CORBA.ORB;
import org.omg.CosNaming.NameComponent;
import org.omg.CosNaming.NamingContextExt;
import org.omg.CosNaming.NamingContextExtHelper;
import org.omg.PortableServer.POA;
import org.omg.PortableServer.POAHelper;
import ProductionControlModule.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Serveur de Contrôle - Version avec Gestion Réparation
 * Les machines en FAILED restent bloquées jusqu'à réparation manuelle
 */
public class ControlServer {
    private static final int CORBA_PORT = 1050;
    private ORB orb;
    private ProductionControlServant servant;

    private final Map<String, MachineInfo> machines = new ConcurrentHashMap<>();
    private final Map<String, IStationCallback> stations = new ConcurrentHashMap<>();

    // Mémoriser les besoins en production (zones vides/basses)
    private final Map<String, Integer> productionNeeds = new ConcurrentHashMap<>();

    // Thread pool pour éviter les blocages
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // Thread pour surveiller les besoins de production
    private final ScheduledExecutorService productionMonitor = Executors.newSingleThreadScheduledExecutor();

    public void start(String[] args) {
        try {
            log("╔═══════════════════════════════════════════════════╗");
            log("║     SERVEUR DE CONTRÔLE - MODE AUTOMATIQUE       ║");
            log("╚═══════════════════════════════════════════════════╝");
            log("");

            Properties props = new Properties();
            props.put("org.omg.CORBA.ORBInitialPort", String.valueOf(CORBA_PORT));
            props.put("org.omg.CORBA.ORBInitialHost", "localhost");
            orb = ORB.init(args, props);

            POA rootPOA = POAHelper.narrow(orb.resolve_initial_references("RootPOA"));
            rootPOA.the_POAManager().activate();

            servant = new ProductionControlServant(this);
            org.omg.CORBA.Object ref = rootPOA.servant_to_reference(servant);
            IProductionControl controlRef = IProductionControlHelper.narrow(ref);

            try {
                org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
                NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
                NameComponent[] path = ncRef.to_name("ProductionControl");
                ncRef.rebind(path, controlRef);
                success("✓ Service CORBA enregistré");
            } catch (Exception e) {
                error("✗ ERREUR: orbd n'est pas démarré!");
                info("  Lancez d'abord: orbd -ORBInitialPort 1050 -ORBInitialHost localhost");
                System.exit(1);
            }

            success("✓ Serveur opérationnel (Port: " + CORBA_PORT + ")");

            // Démarrer la surveillance continue
            startProductionMonitoring();

            divider();
            info("🎛️  EN ATTENTE DE CONNEXIONS...");
            info("🤖 Mode: CONTRÔLE AUTOMATIQUE des machines");
            info("🔄 Surveillance: Toutes les 3 secondes\n");

            orb.run();

        } catch (Exception e) {
            error("Erreur serveur: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Surveillance continue des besoins de production
     * Vérifie toutes les 3 secondes s'il y a des machines disponibles
     */
    private void startProductionMonitoring() {
        productionMonitor.scheduleAtFixedRate(() -> {
            try {
                // Pour chaque type de composant qui a besoin de production
                for (Map.Entry<String, Integer> entry : productionNeeds.entrySet()) {
                    String type = entry.getKey();
                    int level = entry.getValue();

                    // Si le niveau est bas (< 50%), vérifier s'il y a des machines disponibles
                    if (level < 50) {
                        tryStartProductionForType(type);
                    }
                    // Si le niveau est plein (>= 100%), arrêter les machines
                    else if (level >= 100) {
                        stopProductionForZone(type);
                    }
                }
            } catch (Exception e) {
                // Continuer la surveillance même en cas d'erreur
            }
        }, 3, 3, TimeUnit.SECONDS);
    }

    /**
     * Essayer de démarrer la production pour un type
     * Cherche des machines disponibles et les démarre
     * EXCLUT les machines en FAILED (nécessitent réparation)
     */
    private void tryStartProductionForType(String type) {
        // Compter les machines actives pour ce type (exclure FAILED)
        long runningCount = machines.values().stream()
                .filter(m -> m.getMachineType().equals(type))
                .filter(m -> m.getStatus() == MachineStatus.RUNNING)
                .count();

        // Si aucune machine active, essayer d'en démarrer
        if (runningCount == 0) {
            int started = startProductionForZone(type);
            if (started > 0) {
                info("🔄 Auto-démarrage: " + started + " machine(s) pour " + type);
            }
        }
    }

    // === MACHINES ===

    public boolean registerMachine(String machineId, String machineType) {
        MachineInfo info = new MachineInfo(machineId, machineType);
        machines.put(machineId, info);

        divider();
        success("🔧 MACHINE ENREGISTRÉE");
        info("   ID: " + machineId);
        info("   Type: " + machineType);
        info("   État initial: STOPPED");
        info("   Total machines: " + machines.size());

        // Vérifier immédiatement si cette machine est nécessaire
        Integer need = productionNeeds.get(machineType);
        if (need != null && need < 50) {
            info("   ⚡ Production nécessaire pour " + machineType);
            info("   → Démarrage automatique...");

            // Démarrer immédiatement cette machine
            if (startMachine(machineId)) {
                success("   ✓ Machine démarrée automatiquement!");
            }
        }

        divider();

        return true;
    }

    public boolean startMachine(String machineId) {
        MachineInfo machine = machines.get(machineId);
        if (machine != null && machine.getStatus() != MachineStatus.FAILED) {
            machine.setStatus(MachineStatus.RUNNING);
            success("▶️  Machine " + machineId + " → RUNNING");
            return true;
        }
        return false;
    }

    public boolean stopMachine(String machineId) {
        MachineInfo machine = machines.get(machineId);
        if (machine != null) {
            machine.setStatus(MachineStatus.STOPPED);
            success("⏹️  Machine " + machineId + " → STOPPED");
            return true;
        }
        return false;
    }

    public String getMachineStatus(String machineId) {
        MachineInfo machine = machines.get(machineId);
        return machine != null ? machine.getStatus().toString() : "UNKNOWN";
    }

    // === COMPOSANTS ===

    public boolean deliverComponent(Component component) {
        divider();
        info("📦 COMPOSANT REÇU");
        info("   ID: " + component.getComponentId());
        info("   Type: " + component.getType());
        info("   De: " + component.getProducedBy());

        if (component.isDefective()) {
            warning("   ❌ DÉFECTUEUX - Rejeté");
            divider();
            return false;
        }

        // Livrer à une station disponible (en thread séparé)
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
            for (Map.Entry<String, IStationCallback> entry : stations.entrySet()) {
                try {
                    ComponentData data = new ComponentData(
                            component.getComponentId(),
                            component.getType(),
                            component.getProducedBy(),
                            component.isDefective()
                    );

                    boolean accepted = entry.getValue().receiveComponent(data);
                    if (accepted) {
                        success("   ✓ Livré à: " + entry.getKey());
                        return true;
                    }
                } catch (Exception e) {
                    warning("   ! Erreur station " + entry.getKey() + ": " + e.getMessage());
                }
            }
            return false;
        }, executor);

        try {
            boolean result = future.get(2, TimeUnit.SECONDS);
            if (!result) {
                warning("   ⚠️  Aucune station disponible");
            }
            divider();
            return result;
        } catch (TimeoutException e) {
            warning("   ⏱️  Timeout - Station bloquée?");
            divider();
            return false;
        } catch (Exception e) {
            error("   ✗ Erreur: " + e.getMessage());
            divider();
            return false;
        }
    }

    // === STATIONS ===

    public boolean registerStation(String stationId, IStationCallback callback) {
        stations.put(stationId, callback);

        divider();
        success("🏭 STATION ENREGISTRÉE");
        info("   ID: " + stationId);
        info("   Total stations: " + stations.size());
        divider();

        return true;
    }

    /**
     * GESTION AUTOMATIQUE DES ALERTES DE STOCKAGE
     */
    public void handleStorageAlert(String zoneId, int level) {
        // Mémoriser le besoin
        productionNeeds.put(zoneId, level);

        divider();
        warning("📦 ALERTE STOCKAGE");
        info("   Zone: " + zoneId);
        info("   Niveau: " + level + "%");

        if (level == 0) {
            // Zone vide → Démarrer production
            info("   🔴 Zone VIDE!");
            info("   → Action: Démarrer production pour " + zoneId);
            int started = startProductionForZone(zoneId);
            if (started > 0) {
                success("   ✓ " + started + " machine(s) démarrée(s)");
            } else {
                warning("   ⚠️  Aucune machine disponible (elle démarrera à sa connexion)");
            }
        }
        else if (level >= 100) {
            // Zone pleine → Arrêter production
            info("   🔴 Zone PLEINE!");
            info("   → Action: Arrêter production pour " + zoneId);
            int stopped = stopProductionForZone(zoneId);
            if (stopped > 0) {
                success("   ✓ " + stopped + " machine(s) arrêtée(s)");
            }
        }
        else if (level <= 20) {
            // Zone basse → Accélérer production
            info("   🟡 Zone BASSE (" + level + "%)");
            info("   → Action: Augmenter production pour " + zoneId);
            int started = startProductionForZone(zoneId);
            if (started > 0) {
                success("   ✓ " + started + " machine(s) démarrée(s)");
            } else {
                info("   ℹ️  Machines déjà actives ou non disponibles");
            }
        }

        divider();
    }

    /**
     * Démarrer la production pour un type
     * EXCLUT automatiquement les machines en FAILED
     */
    private int startProductionForZone(String zoneId) {
        int startedCount = 0;

        // Trouver machines STOPPED (exclut FAILED et RUNNING)
        List<MachineInfo> availableMachines = machines.values().stream()
                .filter(m -> m.getMachineType().equals(zoneId))
                .filter(m -> m.getStatus() == MachineStatus.STOPPED)  // STOPPED uniquement!
                .collect(Collectors.toList());

        // Démarrer au moins une machine, max 2
        int toStart = Math.min(availableMachines.size(), 2);

        for (int i = 0; i < toStart; i++) {
            MachineInfo machine = availableMachines.get(i);
            if (startMachine(machine.getMachineId())) {
                startedCount++;
            }
        }

        return startedCount;
    }

    /**
     * Arrêter la production pour un type
     */
    private int stopProductionForZone(String zoneId) {
        int stoppedCount = 0;

        List<MachineInfo> runningMachines = machines.values().stream()
                .filter(m -> m.getMachineType().equals(zoneId))
                .filter(m -> m.getStatus() == MachineStatus.RUNNING)
                .collect(Collectors.toList());

        for (MachineInfo machine : runningMachines) {
            if (stopMachine(machine.getMachineId())) {
                stoppedCount++;
            }
        }

        return stoppedCount;
    }

    // === PANNES ===

    /**
     * Gestion automatique des pannes avec remplacement
     * La machine reste en FAILED jusqu'à réparation manuelle
     */
    public String handleFailure(String machineId, String errorType) {
        divider();
        warning("⚠️  PANNE MACHINE");
        info("   ID: " + machineId);
        info("   Erreur: " + errorType);

        MachineInfo failed = machines.get(machineId);
        if (failed == null) {
            error("   ✗ Machine inconnue");
            divider();
            return "ERROR";
        }

        // Marquer comme en panne (DÉFINITIVEMENT jusqu'à réparation)
        failed.setStatus(MachineStatus.FAILED);
        error("   ❌ Machine " + machineId + " → FAILED (nécessite réparation)");

        info("   → Recherche d'un remplacement...");

        // Chercher un remplacement du même type (STOPPED uniquement, pas FAILED)
        String replacement = findReplacement(failed.getMachineType(), machineId);

        if (replacement != null) {
            info("   ✓ Remplacement trouvé: " + replacement);
            startMachine(replacement);
            success("   ✓ Production transférée à " + replacement);
            divider();
            return "REPLACED_BY:" + replacement;
        } else {
            warning("   ! Aucun remplacement disponible");
            warning("   ! Production de " + failed.getMachineType() + " réduite");
            warning("   ! Machine reste en FAILED jusqu'à réparation");
            divider();
            return "NO_REPLACEMENT";
        }
    }

    /**
     * NOUVELLE MÉTHODE : Réparation de machine
     * Remet la machine en état STOPPED
     * Le serveur peut ensuite la redémarrer si besoin
     */
    public boolean handleRepair(String machineId) {
        divider();
        info("🔧 RÉPARATION MACHINE");
        info("   ID: " + machineId);

        MachineInfo machine = machines.get(machineId);
        if (machine == null) {
            error("   ✗ Machine inconnue");
            divider();
            return false;
        }

        if (machine.getStatus() != MachineStatus.FAILED) {
            warning("   ⚠️  Machine pas en panne (état: " + machine.getStatus() + ")");
            divider();
            return false;
        }

        // Remettre en état STOPPED
        machine.setStatus(MachineStatus.STOPPED);
        success("   ✓ Machine " + machineId + " → STOPPED (réparée)");

        // Vérifier si production nécessaire pour ce type
        Integer need = productionNeeds.get(machine.getMachineType());
        if (need != null && need < 50) {
            info("   ⚡ Production nécessaire pour " + machine.getMachineType());
            info("   → Démarrage automatique...");

            // Démarrer immédiatement cette machine
            if (startMachine(machineId)) {
                success("   ✓ Machine redémarrée automatiquement!");
            }
        } else {
            info("   ℹ️  Machine disponible pour production future");
        }

        divider();
        return true;
    }

    /**
     * Trouver une machine de remplacement
     * Cherche STOPPED uniquement (exclut FAILED)
     */
    private String findReplacement(String type, String excludeId) {
        for (Map.Entry<String, MachineInfo> entry : machines.entrySet()) {
            MachineInfo m = entry.getValue();
            if (m.getMachineType().equals(type) &&
                    !m.getMachineId().equals(excludeId) &&
                    m.getStatus() == MachineStatus.STOPPED) {  // STOPPED uniquement!
                return m.getMachineId();
            }
        }
        return null;
    }

    // === STATUS ===

    public String getSystemStatus() {
        StringBuilder sb = new StringBuilder("\n");
        sb.append("╔═══════════════════════════════════════════════════╗\n");
        sb.append("║              ÉTAT DU SYSTÈME                     ║\n");
        sb.append("╠═══════════════════════════════════════════════════╣\n");

        // Compter les machines par état
        long running = machines.values().stream().filter(m -> m.getStatus() == MachineStatus.RUNNING).count();
        long stopped = machines.values().stream().filter(m -> m.getStatus() == MachineStatus.STOPPED).count();
        long failed = machines.values().stream().filter(m -> m.getStatus() == MachineStatus.FAILED).count();

        sb.append("║ Machines: ").append(String.format("%-38d", machines.size())).append("║\n");
        sb.append("║   🟢 En marche: ").append(String.format("%-32d", running)).append("║\n");
        sb.append("║   🔴 Arrêtées: ").append(String.format("%-33d", stopped)).append("║\n");
        sb.append("║   ❌ En panne: ").append(String.format("%-33d", failed)).append("║\n");
        sb.append("╠═══════════════════════════════════════════════════╣\n");

        // Afficher besoins de production
        if (!productionNeeds.isEmpty()) {
            sb.append("║ Besoins de Production:                           ║\n");
            for (Map.Entry<String, Integer> entry : productionNeeds.entrySet()) {
                String type = entry.getKey();
                int level = entry.getValue();
                String status = level == 0 ? "🔴 VIDE" :
                        level >= 100 ? "🔴 PLEIN" :
                                level <= 20 ? "🟡 BAS" : "🟢 OK";
                sb.append("║   ").append(String.format("%-10s", type))
                        .append(" : ").append(String.format("%-8s", status))
                        .append(" (").append(String.format("%3d", level)).append("%)            ║\n");
            }
            sb.append("╠═══════════════════════════════════════════════════╣\n");
        }

        // Afficher par type
        Map<String, List<MachineInfo>> byType = machines.values().stream()
                .collect(Collectors.groupingBy(MachineInfo::getMachineType));

        for (Map.Entry<String, List<MachineInfo>> entry : byType.entrySet()) {
            String type = entry.getKey();
            List<MachineInfo> machineList = entry.getValue();
            sb.append("║ Type: ").append(String.format("%-42s", type)).append("║\n");
            for (MachineInfo m : machineList) {
                String status = m.getStatus() == MachineStatus.RUNNING ? "🟢" :
                        m.getStatus() == MachineStatus.FAILED ? "❌" : "🔴";
                sb.append("║   ").append(status).append(" ")
                        .append(String.format("%-44s", m.getMachineId() + " - " + m.getStatus()))
                        .append("║\n");
            }
        }

        sb.append("╠═══════════════════════════════════════════════════╣\n");
        sb.append("║ Stations: ").append(String.format("%-38d", stations.size())).append("║\n");
        for (String s : stations.keySet()) {
            sb.append("║   ").append(String.format("%-45s", s)).append("║\n");
        }
        sb.append("╚═══════════════════════════════════════════════════╝\n");
        return sb.toString();
    }

    // === AFFICHAGE ===

    private void log(String msg) {
        System.out.println(msg);
    }

    private void divider() {
        System.out.println("───────────────────────────────────────────────────");
    }

    private void success(String msg) {
        System.out.println("✓ " + msg);
    }

    private void warning(String msg) {
        System.out.println("⚠️  " + msg);
    }

    private void error(String msg) {
        System.err.println("✗ " + msg);
    }

    private void info(String msg) {
        System.out.println(msg);
    }

    public static void main(String[] args) {
        ControlServer server = new ControlServer();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n✓ Arrêt du serveur");
        }));

        server.start(args);
    }
}