package client.machines;

import common.Component;
import org.omg.CORBA.ORB;
import org.omg.CosNaming.NamingContextExt;
import org.omg.CosNaming.NamingContextExtHelper;
import ProductionControlModule.IProductionControl;
import ProductionControlModule.IProductionControlHelper;

import java.util.Properties;
import java.util.Random;
import java.util.Scanner;

public class ProductionMachine implements Runnable {
    private String machineId;
    private String machineType;
    private IProductionControl controlRef;
    private int productionRate;
    private boolean isRunning;
    private boolean shouldStop;
    private int productionCount;
    private Random random;
    private long lastStatusCheck;
    private static final long STATUS_CHECK_INTERVAL = 2000; // Vérifier toutes les 2 secondes

    private static final String CORBA_HOST = "localhost";
    private static final int CORBA_PORT = 1050;

    public ProductionMachine(String machineId, String machineType, int productionRate) {
        this.machineId = machineId;
        this.machineType = machineType;
        this.productionRate = productionRate;
        this.isRunning = false;
        this.shouldStop = false;
        this.productionCount = 0;
        this.random = new Random();
        this.lastStatusCheck = System.currentTimeMillis();
    }

    public boolean connectToController() {
        try {
            printHeader("CONNEXION AU CONTRÔLEUR");

            Properties props = new Properties();
            props.put("org.omg.CORBA.ORBInitialHost", CORBA_HOST);
            props.put("org.omg.CORBA.ORBInitialPort", String.valueOf(CORBA_PORT));

            String[] args = new String[0];
            ORB orb = ORB.init(args, props);

            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);

            String name = "ProductionControl";
            controlRef = IProductionControlHelper.narrow(ncRef.resolve_str(name));

            printSuccess("Connecté au service ProductionControl");

            boolean registered = controlRef.registerMachine(machineId, machineType);
            if (registered) {
                printSuccess("Machine " + machineId + " enregistrée");
                printDivider();
                return true;
            } else {
                printError("Échec de l'enregistrement");
                return false;
            }

        } catch (Exception e) {
            printError("Erreur connexion CORBA: " + e.getMessage());
            return false;
        }
    }

    /**
     * CORRECTION: Vérifier le statut côté serveur périodiquement
     */
    private void checkServerStatus() {
        long now = System.currentTimeMillis();
        if (now - lastStatusCheck >= STATUS_CHECK_INTERVAL) {
            try {
                String serverStatus = controlRef.getMachineStatus(machineId);

                // Synchroniser avec le serveur
                if ("RUNNING".equals(serverStatus) && !isRunning) {
                    isRunning = true;
                    printInfo("🔄 Synchronisation: Production activée par le serveur");
                } else if ("STOPPED".equals(serverStatus) && isRunning) {
                    isRunning = false;
                    printInfo("🔄 Synchronisation: Production arrêtée par le serveur");
                } else if ("FAILED".equals(serverStatus)) {
                    isRunning = false;
                    printWarning("⚠️  Machine en panne - Arrêt forcé");
                }

                lastStatusCheck = now;
            } catch (Exception e) {
                printError("Erreur vérification statut: " + e.getMessage());
            }
        }
    }

    public void startProduction() {
        if (controlRef != null) {
            try {
                boolean started = controlRef.requestProductionStart(machineId);
                if (started) {
                    isRunning = true;
                    printSuccess("▶️  Production démarrée");
                } else {
                    printError("Impossible de démarrer la production");
                }
            } catch (Exception e) {
                printError("Erreur démarrage: " + e.getMessage());
            }
        }
    }

    public void stopProduction() {
        if (controlRef != null) {
            try {
                boolean stopped = controlRef.requestProductionStop(machineId);
                if (stopped) {
                    isRunning = false;
                    printSuccess("⏹️  Production arrêtée");
                } else {
                    printError("Impossible d'arrêter la production");
                }
            } catch (Exception e) {
                printError("Erreur arrêt: " + e.getMessage());
            }
        }
    }

    public void simulateFailure(String errorType) {
        if (controlRef != null) {
            try {
                printWarning("\n⚠️  SIMULATION DE PANNE: " + errorType);
                String response = controlRef.notifyFailure(machineId, errorType);
                printInfo("→ Réponse du contrôleur: " + response);

                if (response.startsWith("REPLACED_BY:")) {
                    String replacementId = response.substring("REPLACED_BY:".length());
                    printInfo("→ Remplacé par: " + replacementId);
                    isRunning = false;
                    shouldStop = true;
                }
            } catch (Exception e) {
                printError("Erreur notification panne: " + e.getMessage());
            }
        }
    }

    public Component produceComponent() {
        String componentId = machineId + "-C" + (++productionCount);
        Component component = new Component(componentId, machineType, machineId);

        if (random.nextInt(100) < 5) {
            component.setDefective(true);
        }

        return component;
    }

    @Override
    public void run() {
        printHeader("MACHINE " + machineId + " OPÉRATIONNELLE");
        System.out.println("Type: " + machineType);
        System.out.println("Taux de production: " + productionRate + "ms");
        printDivider();
        printInfo("💡 En attente de commandes...\n");

        while (!shouldStop) {
            // CORRECTION: Vérifier le statut côté serveur
            checkServerStatus();

            if (isRunning) {
                try {
                    Component component = produceComponent();
                    printSuccess("🔧 Produit [" + productionCount + "]: " + component.getComponentId());

                    // Simuler une panne aléatoire (2% de chance)
                    if (random.nextInt(100) < 2) {
                        simulateFailure("MECHANICAL_FAILURE");
                    }

                    Thread.sleep(productionRate);

                } catch (InterruptedException e) {
                    printWarning("Production interrompue");
                    break;
                } catch (Exception e) {
                    printError("Erreur de production: " + e.getMessage());
                }
            } else {
                // Machine arrêtée, attendre
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        printDivider();
        printInfo("🏁 Machine " + machineId + " arrêtée");
        printInfo("📊 Total produit: " + productionCount + " composants");
        printDivider();
    }

    public void shutdown() {
        shouldStop = true;
        isRunning = false;
    }

    // Méthodes d'affichage
    private void printHeader(String title) {
        System.out.println("\n╔═══════════════════════════════════════════════════╗");
        System.out.println("║  " + centerText(title, 47) + "  ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
    }

    private void printDivider() {
        System.out.println("───────────────────────────────────────────────────");
    }

    private void printSuccess(String msg) {
        System.out.println("✓ " + msg);
    }

    private void printWarning(String msg) {
        System.out.println(msg);
    }

    private void printError(String msg) {
        System.err.println("✗ " + msg);
    }

    private void printInfo(String msg) {
        System.out.println(msg);
    }

    private String centerText(String text, int width) {
        int padding = (width - text.length()) / 2;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < padding; i++) sb.append(" ");
        sb.append(text);
        while (sb.length() < width) sb.append(" ");
        return sb.toString();
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║      CONFIGURATION MACHINE DE PRODUCTION         ║");
        System.out.println("╚═══════════════════════════════════════════════════╝\n");

        System.out.print("ID Machine (ex: M1): ");
        String machineId = scanner.nextLine();

        System.out.print("Type Machine (ex: TYPE_A): ");
        String machineType = scanner.nextLine();

        System.out.print("Taux de production en ms (ex: 3000): ");
        int rate = scanner.nextInt();
        scanner.nextLine();

        ProductionMachine machine = new ProductionMachine(machineId, machineType, rate);

        if (!machine.connectToController()) {
            System.err.println("❌ Impossible de se connecter au contrôleur");
            System.err.println("💡 Assurez-vous que le serveur est démarré");
            return;
        }

        Thread machineThread = new Thread(machine);
        machineThread.start();

        System.out.println("\n╔═══════════════════════════════════════════════════╗");
        System.out.println("║              MENU MACHINE " + machineId + "                    ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.println("║  1. ▶️  Démarrer production                        ║");
        System.out.println("║  2. ⏹️  Arrêter production                         ║");
        System.out.println("║  3. ⚠️  Simuler panne                             ║");
        System.out.println("║  4. 📊 Afficher statut                            ║");
        System.out.println("║  5. 🚪 Quitter                                     ║");
        System.out.println("╚═══════════════════════════════════════════════════╝\n");

        boolean quit = false;
        while (!quit) {
            System.out.print("Choix: ");
            String choice = scanner.nextLine();

            switch (choice) {
                case "1":
                    machine.startProduction();
                    break;

                case "2":
                    machine.stopProduction();
                    break;

                case "3":
                    System.out.print("Type d'erreur (MECHANICAL_FAILURE, OVERHEATING, etc.): ");
                    String errorType = scanner.nextLine();
                    machine.simulateFailure(errorType);
                    break;

                case "4":
                    System.out.println("\n╔═══════════════════════════════════════════════════╗");
                    System.out.println("║               STATUT MACHINE " + machineId + "                 ║");
                    System.out.println("╠═══════════════════════════════════════════════════╣");
                    System.out.println("║  État: " + (machine.isRunning ? "🟢 EN MARCHE       " : "🔴 ARRÊTÉE        ") + "                     ║");
                    System.out.println("║  Production: " + machine.productionCount + " composants                    ║");
                    System.out.println("╚═══════════════════════════════════════════════════╝");
                    break;

                case "5":
                    machine.shutdown();
                    quit = true;
                    System.out.println("\n👋 Arrêt de la machine " + machineId);
                    break;

                default:
                    System.out.println("❌ Choix invalide");
            }
        }

        scanner.close();
        System.exit(0);
    }
}