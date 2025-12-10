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

/**
 * Machine de production (Client CORBA)
 * Communique avec le contrôleur via CORBA
 */
public class ProductionMachine implements Runnable {

    private String machineId;
    private String machineType;
    private IProductionControl controlRef;
    private int productionRate; // millisecondes entre productions
    private boolean isRunning;
    private boolean shouldStop;
    private int productionCount;
    private Random random;

    // Configuration CORBA
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
    }

    /**
     * Connexion au contrôleur via CORBA
     */
    public boolean connectToController() {
        try {
            System.out.println("\n=== Connexion au contrôleur CORBA ===");

            // Créer et initialiser l'ORB
            Properties props = new Properties();
            props.put("org.omg.CORBA.ORBInitialHost", CORBA_HOST);
            props.put("org.omg.CORBA.ORBInitialPort", String.valueOf(CORBA_PORT));

            String[] args = new String[0];
            ORB orb = ORB.init(args, props);

            // Obtenir le contexte de nommage
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);

            // Obtenir la référence au service ProductionControl
            String name = "ProductionControl";
            controlRef = IProductionControlHelper.narrow(ncRef.resolve_str(name));

            System.out.println("✓ Connecté au service ProductionControl");

            // Enregistrer la machine auprès du contrôleur
            boolean registered = controlRef.registerMachine(machineId, machineType);
            if (registered) {
                System.out.println("✓ Machine " + machineId + " enregistrée avec succès");
                return true;
            } else {
                System.err.println("✗ Échec de l'enregistrement");
                return false;
            }

        } catch (Exception e) {
            System.err.println("✗ Erreur de connexion CORBA: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Démarrer la production
     */
    public void startProduction() {
        if (controlRef != null) {
            boolean started = controlRef.requestProductionStart(machineId);
            if (started) {
                isRunning = true;
                System.out.println("\n✓ Production démarrée pour " + machineId);
            }
        }
    }

    /**
     * Arrêter la production
     */
    public void stopProduction() {
        if (controlRef != null) {
            boolean stopped = controlRef.requestProductionStop(machineId);
            if (stopped) {
                isRunning = false;
                System.out.println("\n✓ Production arrêtée pour " + machineId);
            }
        }
    }

    /**
     * Simuler une panne
     */
    public void simulateFailure(String errorType) {
        if (controlRef != null) {
            System.out.println("\n⚠ SIMULATION DE PANNE: " + errorType);
            String response = controlRef.notifyFailure(machineId, errorType);
            System.out.println("→ Réponse du contrôleur: " + response);

            if (response.startsWith("REPLACED_BY:")) {
                String replacementId = response.substring("REPLACED_BY:".length());
                System.out.println("→ Remplacé par: " + replacementId);
                isRunning = false;
            }
        }
    }

    /**
     * Produire un composant
     */
    public Component produceComponent() {
        String componentId = machineId + "-C" + (++productionCount);
        Component component = new Component(componentId, machineType, machineId);

        // Simuler un défaut aléatoire (5% de chance)
        if (random.nextInt(100) < 5) {
            component.setDefective(true);
        }

        return component;
    }

    /**
     * Boucle de production principale
     */
    @Override
    public void run() {
        System.out.println("\n" + machineId + " prêt à produire (Type: " + machineType + ")");

        while (!shouldStop) {
            if (isRunning) {
                try {
                    // Produire un composant
                    Component component = produceComponent();
                    System.out.println(machineId + " → Produit: " + component);

                    // Simuler une panne aléatoire (2% de chance)
                    if (random.nextInt(100) < 2) {
                        simulateFailure("MECHANICAL_FAILURE");
                    }

                    // Attendre selon le taux de production
                    Thread.sleep(productionRate);

                } catch (InterruptedException e) {
                    System.err.println("Production interrompue");
                    break;
                } catch (Exception e) {
                    System.err.println("Erreur de production: " + e.getMessage());
                }
            } else {
                // Machine arrêtée, attendre
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        System.out.println(machineId + " arrêté. Total produit: " + productionCount);
    }

    /**
     * Arrêter la machine
     */
    public void shutdown() {
        shouldStop = true;
        isRunning = false;
    }

    /**
     * Main - Menu interactif pour la machine
     */
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("=== Configuration Machine de Production ===");
        System.out.print("ID Machine (ex: M1): ");
        String machineId = scanner.nextLine();

        System.out.print("Type Machine (ex: TYPE_A, TYPE_B): ");
        String machineType = scanner.nextLine();

        System.out.print("Taux de production (ms, ex: 3000): ");
        int rate = scanner.nextInt();
        scanner.nextLine(); // consommer le \n

        ProductionMachine machine = new ProductionMachine(machineId, machineType, rate);

        // Connexion au contrôleur
        if (!machine.connectToController()) {
            System.err.println("Impossible de se connecter au contrôleur");
            return;
        }

        // Démarrer le thread de production
        Thread machineThread = new Thread(machine);
        machineThread.start();

        // Menu interactif
        System.out.println("\n=== Menu Machine " + machineId + " ===");
        System.out.println("1. Démarrer production");
        System.out.println("2. Arrêter production");
        System.out.println("3. Simuler panne");
        System.out.println("4. Afficher statut");
        System.out.println("5. Quitter");

        boolean quit = false;
        while (!quit) {
            System.out.print("\nChoix: ");
            String choice = scanner.nextLine();

            switch (choice) {
                case "1":
                    machine.startProduction();
                    break;

                case "2":
                    machine.stopProduction();
                    break;

                case "3":
                    System.out.print("Type d'erreur (ex: MECHANICAL_FAILURE): ");
                    String errorType = scanner.nextLine();
                    machine.simulateFailure(errorType);
                    break;

                case "4":
                    System.out.println("\nStatut: " + (machine.isRunning ? "EN MARCHE" : "ARRÊTÉE"));
                    System.out.println("Production: " + machine.productionCount + " composants");
                    break;

                case "5":
                    machine.shutdown();
                    quit = true;
                    break;

                default:
                    System.out.println("Choix invalide");
            }
        }

        scanner.close();
        System.out.println("Machine " + machineId + " terminée");
        System.exit(0);
    }
}