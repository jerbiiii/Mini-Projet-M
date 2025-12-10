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

public class ProductionMachine {
    private String machineId;
    private String machineType;
    private int productionRate;
    private IProductionControl controlRef;

    private boolean isRunning = false;
    private boolean shouldStop = false;
    private int productionCount = 0;
    private Random random = new Random();

    // Thread séparé pour la production
    private ScheduledExecutorService productionThread;

    public ProductionMachine(String machineId, String machineType, int productionRate) {
        this.machineId = machineId;
        this.machineType = machineType;
        this.productionRate = productionRate;
        this.productionThread = Executors.newSingleThreadScheduledExecutor();
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
                return true;
            }
            return false;

        } catch (Exception e) {
            error("✗ ERREUR: " + e.getMessage());
            info("  Vérifiez que le serveur est démarré");
            return false;
        }
    }

    public void startProduction() {
        if (isRunning) {
            warning("⚠️  Production déjà en cours");
            return;
        }

        try {
            if (controlRef.requestProductionStart(machineId)) {
                isRunning = true;

                divider();
                success("▶️  PRODUCTION DÉMARRÉE");
                info("   Taux: 1 pièce toutes les " + (productionRate/1000) + "s");
                divider();
                log("");

                // Démarrer la production en boucle
                productionThread.scheduleAtFixedRate(() -> {
                    if (isRunning) {
                        produce();
                    }
                }, 0, productionRate, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            error("✗ Erreur: " + e.getMessage());
        }
    }

    public void stopProduction() {
        if (!isRunning) {
            warning("⚠️  Production déjà arrêtée");
            return;
        }

        try {
            if (controlRef.requestProductionStop(machineId)) {
                isRunning = false;

                divider();
                success("⏹️  PRODUCTION ARRÊTÉE");
                info("   Total produit: " + productionCount + " pièces");
                divider();
                log("");
            }
        } catch (Exception e) {
            error("✗ Erreur: " + e.getMessage());
        }
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
            info("   ID: " + compId);
            info("   Type: " + machineType);

            if (comp.isDefective()) {
                warning("   Qualité: DÉFECTUEUX");
            } else {
                success("   Qualité: OK");
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
                success("   ✓ Livré au serveur");
            } else {
                warning("   ⚠️  En attente (station occupée)");
            }
            divider();
            log("");

            // 1% de chance de panne
            if (random.nextInt(100) < 1) {
                simulateFailure("MECHANICAL_FAILURE");
            }

        } catch (Exception e) {
            error("✗ Erreur production: " + e.getMessage());
        }
    }

    public void simulateFailure(String errorType) {
        try {
            divider();
            warning("⚠️  SIMULATION DE PANNE");
            info("   Machine: " + machineId);
            info("   Erreur: " + errorType);

            String response = controlRef.notifyFailure(machineId, errorType);
            info("   Réponse serveur: " + response);

            if (response.startsWith("REPLACED_BY:")) {
                String replacement = response.substring("REPLACED_BY:".length());
                success("   ✓ Remplacée par: " + replacement);
                isRunning = false;
                shouldStop = true;
            } else {
                warning("   ! Aucun remplacement disponible");
                isRunning = false;
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
        log("│ État: " + (isRunning ? "🟢 EN MARCHE" : "🔴 ARRÊTÉE"));
        log("│ Type: " + machineType);
        log("│ Production: " + productionCount + " pièces");
        log("│ Taux: " + (productionRate/1000) + "s / pièce");
        divider();
        log("");
    }

    public void shutdown() {
        shouldStop = true;
        isRunning = false;
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

        // Menu interactif
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║                MENU MACHINE                      ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.println("║  1. Démarrer production                          ║");
        System.out.println("║  2. Arrêter production                           ║");
        System.out.println("║  3. Simuler panne                                ║");
        System.out.println("║  4. Afficher état                                ║");
        System.out.println("║  5. Quitter                                      ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
        System.out.println();

        while (!machine.shouldStop) {
            System.out.print(id + " > ");
            String choice = sc.nextLine().trim();

            switch (choice) {
                case "1":
                    machine.startProduction();
                    break;

                case "2":
                    machine.stopProduction();
                    break;

                case "3":
                    System.out.print("Type erreur (ex: MECHANICAL_FAILURE): ");
                    String errorType = sc.nextLine().trim();
                    machine.simulateFailure(errorType);
                    break;

                case "4":
                    machine.showStatus();
                    break;

                case "5":
                    System.out.println("\n👋 Arrêt de la machine " + id);
                    machine.shutdown();
                    System.exit(0);
                    break;

                default:
                    System.out.println("❌ Commande invalide (1, 2, 3, 4 ou 5)");
            }
        }
    }
}