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

public class ControlServer {
    private static final int CORBA_PORT = 1050;
    private ORB orb;
    private ProductionControlServant servant;

    private final Map<String, MachineInfo> machines = new ConcurrentHashMap<>();
    private final Map<String, IStationCallback> stations = new ConcurrentHashMap<>();
    private final Map<String, Integer> storageStatus = new ConcurrentHashMap<>();

    // Thread pool pour éviter les blocages
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public void start(String[] args) {
        try {
            log("╔═══════════════════════════════════════════════════╗");
            log("║     SERVEUR DE CONTRÔLE - VERSION FINALE         ║");
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
            divider();
            info("🎛️  EN ATTENTE DE CONNEXIONS...\n");

            orb.run();

        } catch (Exception e) {
            error("Erreur serveur: " + e.getMessage());
            e.printStackTrace();
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
        info("   Total machines: " + machines.size());
        divider();

        return true;
    }

    public boolean startMachine(String machineId) {
        MachineInfo machine = machines.get(machineId);
        if (machine != null && machine.getStatus() != MachineStatus.FAILED) {
            machine.setStatus(MachineStatus.RUNNING);
            success("▶️  Machine " + machineId + " démarrée");
            return true;
        }
        return false;
    }

    public boolean stopMachine(String machineId) {
        MachineInfo machine = machines.get(machineId);
        if (machine != null) {
            machine.setStatus(MachineStatus.STOPPED);
            success("⏹️  Machine " + machineId + " arrêtée");
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

        // Utiliser un thread séparé pour éviter le deadlock
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
            for (Map.Entry<String, IStationCallback> entry : stations.entrySet()) {
                try {
                    ComponentData data = new ComponentData(
                            component.getComponentId(),
                            component.getType(),
                            component.getProducedBy(),
                            component.isDefective()
                    );

                    // Appel CORBA avec timeout implicite
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
            // Attendre max 2 secondes
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

    public void handleStorageAlert(String zoneId, int level) {
        divider();
        warning("📦 ALERTE STOCKAGE");
        info("   Zone: " + zoneId);
        info("   Niveau: " + level + "%");

        if (level == 0) {
            info("   → Action: Démarrer production " + zoneId);
            startProductionForZone(zoneId);
        } else if (level >= 100) {
            info("   → Action: Arrêter production " + zoneId);
            stopProductionForZone(zoneId);
        }
        divider();
    }

    private void startProductionForZone(String zoneId) {
        for (MachineInfo machine : machines.values()) {
            if (machine.getMachineType().equals(zoneId) &&
                    machine.getStatus() == MachineStatus.STOPPED) {
                startMachine(machine.getMachineId());
                break;
            }
        }
    }

    private void stopProductionForZone(String zoneId) {
        for (MachineInfo machine : machines.values()) {
            if (machine.getMachineType().equals(zoneId) &&
                    machine.getStatus() == MachineStatus.RUNNING) {
                stopMachine(machine.getMachineId());
            }
        }
    }

    // === PANNES ===

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

        failed.setStatus(MachineStatus.FAILED);

        String replacement = findReplacement(failed.getMachineType(), machineId);
        if (replacement != null) {
            info("   ✓ Remplacement: " + replacement);
            stopMachine(machineId);
            startMachine(replacement);
            divider();
            return "REPLACED_BY:" + replacement;
        } else {
            warning("   ! Pas de remplacement disponible");
            stopMachine(machineId);
            divider();
            return "NO_REPLACEMENT";
        }
    }

    private String findReplacement(String type, String excludeId) {
        for (Map.Entry<String, MachineInfo> entry : machines.entrySet()) {
            MachineInfo m = entry.getValue();
            if (m.getMachineType().equals(type) &&
                    !m.getMachineId().equals(excludeId) &&
                    m.getStatus() == MachineStatus.STOPPED) {
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
        sb.append("║ Machines: ").append(String.format("%-38d", machines.size())).append("║\n");
        for (MachineInfo m : machines.values()) {
            sb.append("║   ").append(String.format("%-45s", m.toString())).append("║\n");
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