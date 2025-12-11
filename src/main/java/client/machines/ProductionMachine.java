package client.machines;

import common.Component;
import org.omg.CORBA.ORB;
import org.omg.CosNaming.NamingContextExt;
import org.omg.CosNaming.NamingContextExtHelper;
import ProductionControlModule.*;

import java.util.Properties;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.*;

/**
 * Machine de Production - Version avec Réparation
 * Les machines en panne restent bloquées jusqu'à réparation manuelle
 */
public class ProductionMachine {
    private String machineId;
    private String machineType;
    private int productionRate;
    private IProductionControl controlRef;

    private volatile boolean isRunning = false;
    private volatile boolean shouldStop = false;
    private volatile boolean isFailed = false;  // NOUVEAU
    private volatile String lastKnownStatus = "STOPPED";
    private int productionCount = 0;
    private Random random = new Random();

    // Thread séparé pour la production
    private ScheduledExecutorService productionThread;
    private ScheduledFuture<?> productionTask;

    // Thread pour vérifier l'état depuis le serveur
    private ScheduledExecutorService statusChecker;

    public ProductionMachine(String machineId, String machineType, int productionRate) {
        this.machineId = machineId;
        this.machineType = machineType;
        this.productionRate = productionRate;
        this.productionThread = Executors.newSingleThreadScheduledExecutor();
        this.statusChecker = Executors.newSingleThreadScheduledExecutor();
    }

    public boolean connect() {
        try {
            log("╔═══════════════════════════════════════════════════╗");
            log("║          CONNEXION AU SERVEUR                    ║");
            log("╚═══════════════════════════════════════════════════╝");
            log("");

            Properties props = new Properties();
            props.put("org.omg.CORBA.ORBInitialPort", "1050");
            props.put("org.omg.CORBA.ORBInitialHost", "localhost");

            ORB orb = ORB.init(new String[0], props);
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);

            controlRef = IProductionControlHelper.narrow(ncRef.resolve_str("ProductionControl"));

            if (controlRef.registerMachine(machineId, machineType)) {
                success("✓ Machine enregistrée: " + machineId);
                divider();
                log("");

                // Démarrer la surveillance de l'état
                startStatusMonitoring();

                info("💡 Machine en mode automatique");
                info("   Type: " + machineType);
                info("   Le serveur contrôle le démarrage/arrêt");
                info("   Surveillance: Toutes les 1 seconde\n");

                return true;
            }
            return false;

        } catch (Exception e) {
            error("✗ ERREUR: " + e.getMessage());
            info("  Vérifiez que le serveur est démarré");
            return false;
        }
    }

    /**
     * Surveillance rapide de l'état depuis le serveur
     */
    private void startStatusMonitoring() {
        statusChecker.scheduleAtFixedRate(() -> {
            try {
                String currentStatus = controlRef.getMachineStatus(machineId);

                // Détection de changement d'état
                if (!currentStatus.equals(lastKnownStatus)) {
                    divider();
                    info("🔄 CHANGEMENT D'ÉTAT DÉTECTÉ");
                    info("   Ancien: " + lastKnownStatus);
                    info("   Nouveau: " + currentStatus);
                    divider();
                    log("");

                    lastKnownStatus = currentStatus;
                }

                // Réagir selon l'état
                if ("RUNNING".equals(currentStatus) && !isRunning && !isFailed) {
                    // Le serveur demande le démarrage
                    startProductionInternal();
                }
                else if ("STOPPED".equals(currentStatus) && isRunning) {
                    // Le serveur demande l'arrêt
                    stopProductionInternal();
                }
                else if ("FAILED".equals(currentStatus)) {
                    // Machine en panne
                    if (isRunning) {
                        stopProductionInternal();
                    }
                    isFailed = true;
                }

            } catch (Exception e) {
                // Ignorer les erreurs temporaires
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Démarrage interne de la production
     */
    private synchronized void startProductionInternal() {
        if (isRunning || shouldStop || isFailed) {
            return;
        }

        isRunning = true;

        divider();
        success("▶️  PRODUCTION DÉMARRÉE (par serveur)");
        info("   Machine: " + machineId);
        info("   Type: " + machineType);
        info("   Taux: 1 pièce toutes les " + (productionRate/1000) + "s");
        divider();
        log("");

        // Démarrer la production en boucle
        productionTask = productionThread.scheduleAtFixedRate(() -> {
            if (isRunning && !shouldStop && !isFailed) {
                produce();
            }
        }, 0, productionRate, TimeUnit.MILLISECONDS);
    }

    /**
     * Arrêt interne de la production
     */
    private synchronized void stopProductionInternal() {
        if (!isRunning) {
            return;
        }

        isRunning = false;
        if (productionTask != null) {
            productionTask.cancel(false);
        }

        divider();
        success("⏹️  PRODUCTION ARRÊTÉE (par serveur)");
        info("   Machine: " + machineId);
        info("   Total produit: " + productionCount + " pièces");
        divider();
        log("");
    }

    private void produce() {
        try {
            // Créer composant
            String compId = machineId + "-C" + (++productionCount);
            Component comp = new Component(compId, machineType, machineId);

            // 5% de chance d'être défectueux
            if (random.nextInt(100) < 5) {
                comp.setDefective(true);
            }

            divider();
            info("🔧 PRODUCTION [" + productionCount + "]");
            info("   Machine: " + machineId);
            info("   Composant: " + compId);
            info("   Type: " + machineType);

            if (comp.isDefective()) {
                warning("   Qualité: ❌ DÉFECTUEUX");
            } else {
                success("   Qualité: ✓ OK");
            }

            // Envoyer au serveur
            ComponentData data = new ComponentData(
                    comp.getComponentId(),
                    comp.getType(),
                    comp.getProducedBy(),
                    comp.isDefective()
            );

            info("   → Envoi au serveur...");
            boolean sent = controlRef.deliverComponent(data);

            if (sent) {
                success("   ✓ Livré avec succès");
            } else {
                warning("   ⚠️  En attente (station occupée)");
            }
            divider();
            log("");

            // 1% de chance de panne automatique
            if (random.nextInt(100) < 1) {
                simulateFailure("MECHANICAL_FAILURE_AUTO");
            }

        } catch (Exception e) {
            error("✗ Erreur production: " + e.getMessage());
        }
    }

    /**
     * Simulation de panne (manuelle ou automatique)
     * Machine reste BLOQUÉE jusqu'à réparation
     */
    public void simulateFailure(String errorType) {
        try {
            divider();
            warning("⚠️  PANNE DÉTECTÉE");
            info("   Machine: " + machineId);
            info("   Erreur: " + errorType);
            info("   → Notification au serveur...");

            String response = controlRef.notifyFailure(machineId, errorType);
            info("   Réponse serveur: " + response);

            // Arrêter la production
            stopProductionInternal();
            isFailed = true;

            if (response.startsWith("REPLACED_BY:")) {
                String replacement = response.substring("REPLACED_BY:".length());
                success("   ✓ Remplacée par: " + replacement);
                error("   ❌ Cette machine est EN PANNE");
                info("   🔧 Utilisez 'Corriger panne' pour la réparer");
            } else if (response.equals("NO_REPLACEMENT")) {
                warning("   ! Aucun remplacement disponible");
                error("   ❌ Machine EN PANNE - Production arrêtée");
                info("   🔧 Utilisez 'Corriger panne' pour la réparer");
            }
            divider();
            log("");

        } catch (Exception e) {
            error("✗ Erreur: " + e.getMessage());
        }
    }

    /**
     * NOUVELLE MÉTHODE : Correction de panne
     * Notifie le serveur que la machine est réparée
     * Le serveur peut alors la redémarrer si besoin
     */
    public void repairMachine() {
        if (!isFailed) {
            warning("⚠️  Machine pas en panne!");
            return;
        }

        try {
            divider();
            info("🔧 RÉPARATION EN COURS");
            info("   Machine: " + machineId);
            info("   → Notification au serveur...");

            boolean success = controlRef.notifyRepair(machineId);

            if (success) {
                success("   ✓ Réparation confirmée par le serveur");
                isFailed = false;
                info("   ✓ Machine remise en état STOPPED");
                info("   💡 Le serveur peut la redémarrer si nécessaire");
            } else {
                error("   ✗ Erreur lors de la réparation");
            }

            divider();
            log("");

        } catch (Exception e) {
            error("✗ Erreur: " + e.getMessage());
        }
    }

    public void showStatus() {
        divider();
        log("│           ÉTAT MACHINE " + machineId);
        divider();
        log("│ Type: " + machineType);
        log("│ État serveur: " + lastKnownStatus);
        log("│ Production locale: " + (isRunning ? "🟢 EN COURS" : "🔴 ARRÊTÉE"));
        log("│ En panne: " + (isFailed ? "❌ OUI (nécessite réparation)" : "✓ NON"));
        log("│ Pièces produites: " + productionCount);
        log("│ Taux: " + (productionRate/1000) + "s / pièce");
        log("│ Mode: AUTOMATIQUE (contrôlé par serveur)");
        divider();
        log("");
    }

    public void shutdown() {
        shouldStop = true;
        isRunning = false;
        statusChecker.shutdown();
        productionThread.shutdown();
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

    // === MAIN ===

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);

        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║       CONFIGURATION MACHINE DE PRODUCTION        ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
        System.out.println();

        System.out.print("ID Machine (ex: M1): ");
        String id = sc.nextLine().trim();

        System.out.print("Type (ex: TYPE_A): ");
        String type = sc.nextLine().trim();

        System.out.print("Taux production en ms (ex: 3000): ");
        int rate = Integer.parseInt(sc.nextLine().trim());

        ProductionMachine machine = new ProductionMachine(id, type, rate);

        if (!machine.connect()) {
            System.exit(1);
        }

        // Menu avec option de réparation
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║           MENU MACHINE (MODE AUTO)               ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.println("║  1. Afficher état                                ║");
        System.out.println("║  2. Simuler panne                                ║");
        System.out.println("║  3. Corriger panne                               ║");
        System.out.println("║  4. Quitter                                      ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("ℹ️  La machine démarre/arrête automatiquement");
        System.out.println("   selon les besoins détectés par le serveur");
        System.out.println("   Une machine en panne nécessite une réparation\n");

        while (!machine.shouldStop) {
            System.out.print(id + " > ");
            String choice = sc.nextLine().trim();

            switch (choice) {
                case "1":
                    machine.showStatus();
                    break;

                case "2":
                    System.out.print("Type erreur (ex: MECHANICAL_FAILURE): ");
                    String errorType = sc.nextLine().trim();
                    machine.simulateFailure(errorType);
                    break;

                case "3":
                    machine.repairMachine();
                    break;

                case "4":
                    System.out.println("\n👋 Arrêt de la machine " + id);
                    machine.shutdown();
                    System.exit(0);
                    break;

                default:
                    System.out.println("❌ Commande invalide (1, 2, 3 ou 4)");
            }
        }
    }
}