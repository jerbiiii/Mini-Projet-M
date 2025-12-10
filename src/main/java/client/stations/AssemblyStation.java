package client.stations;

import common.Component;
import common.Message;
import common.Product;
import org.omg.CORBA.ORB;
import org.omg.CosNaming.NamingContextExt;
import org.omg.CosNaming.NamingContextExtHelper;
import ProductionControlModule.IStationControl;
import ProductionControlModule.IStationControlHelper;

import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AssemblyStation implements Runnable {
    private String stationId;
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private IStationControl stationControlRef;

    private Map<String, Queue<Component>> storageZones;
    private int maxCapacity = 10;
    private int minCapacity = 2;

    private int assembledProducts = 0;
    private boolean isRunning = true;
    private boolean simulationMode = false; // CORRECTION: Désactivé par défaut

    private static final String SERVER_HOST = "localhost";
    private static final int SOCKET_PORT = 5000;
    private static final String CORBA_HOST = "localhost";
    private static final int CORBA_PORT = 1050;

    public AssemblyStation(String stationId, String[] componentTypes) {
        this.stationId = stationId;
        this.storageZones = new ConcurrentHashMap<>();

        for (String type : componentTypes) {
            storageZones.put(type, new ConcurrentLinkedQueue<>());
        }
    }

    public boolean connectToController() {
        try {
            printHeader("CONNEXION AU CONTRÔLEUR");

            // Connexion CORBA
            Properties props = new Properties();
            props.put("org.omg.CORBA.ORBInitialHost", CORBA_HOST);
            props.put("org.omg.CORBA.ORBInitialPort", String.valueOf(CORBA_PORT));

            String[] args = new String[0];
            ORB orb = ORB.init(args, props);

            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);

            String name = "StationControl";
            stationControlRef = IStationControlHelper.narrow(ncRef.resolve_str(name));

            printSuccess("Connecté au service StationControl");

            boolean registered = stationControlRef.registerAssemblyStation(stationId);
            if (registered) {
                printSuccess("Station " + stationId + " enregistrée");
            }

            // Connexion Socket
            socket = new Socket(SERVER_HOST, SOCKET_PORT);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            printSuccess("Connecté au serveur Socket");
            printDivider();

            return true;

        } catch (Exception e) {
            printError("Erreur connexion: " + e.getMessage());
            return false;
        }
    }

    /**
     * CORRECTION: Ne vérifie les niveaux que s'il y a eu un changement
     */
    public void checkStorageLevels() {
        for (Map.Entry<String, Queue<Component>> entry : storageZones.entrySet()) {
            String zoneId = entry.getKey();
            int level = entry.getValue().size();

            // Alerte si zone vide
            if (level == 0) {
                printWarning("⚠️  Zone " + zoneId + " VIDE");
                sendStorageAlert(zoneId, 0);
            }
            // Alerte si zone pleine
            else if (level >= maxCapacity) {
                printWarning("⚠️  Zone " + zoneId + " PLEINE (" + level + "/" + maxCapacity + ")");
                sendStorageAlert(zoneId, 100);
            }
            // Alerte si niveau bas
            else if (level <= minCapacity && level > 0) {
                printWarning("⚠️  Zone " + zoneId + " NIVEAU BAS (" + level + "/" + maxCapacity + ")");
                sendStorageAlert(zoneId, level * 10);
            }
        }
    }

    public void sendStorageAlert(String zoneId, int level) {
        if (out != null) {
            Message alert = new Message(Message.TYPE_STORAGE_ALERT, stationId,
                    zoneId + ":" + level);
            out.println(alert.serialize());

            try {
                String response = in.readLine();
                // Réponse reçue
            } catch (IOException e) {
                printError("Erreur lecture réponse: " + e.getMessage());
            }
        }
    }

    public boolean addComponent(Component component) {
        Queue<Component> zone = storageZones.get(component.getType());
        if (zone != null && zone.size() < maxCapacity) {
            zone.offer(component);
            printSuccess("📦 Composant reçu: " + component.getComponentId() +
                    " (Type: " + component.getType() + ")");
            displayStorageStatus();

            // Vérifier les niveaux après ajout
            checkStorageLevels();
            return true;
        } else if (zone != null && zone.size() >= maxCapacity) {
            printWarning("❌ Zone " + component.getType() + " pleine! Composant rejeté.");
            return false;
        }
        return false;
    }

    public Product assembleProduct() {
        // Vérifier si on a au moins un composant de chaque type
        for (Queue<Component> zone : storageZones.values()) {
            if (zone.isEmpty()) {
                return null;
            }
        }

        // Créer un nouveau produit
        String productId = stationId + "-P" + (++assembledProducts);
        Product product = new Product(productId, storageZones.size());

        // Prélever un composant de chaque zone
        for (Queue<Component> zone : storageZones.values()) {
            Component component = zone.poll();
            if (component != null) {
                product.addComponent(component);
            }
        }

        return product;
    }

    /**
     * CORRECTION: Simulation désactivée par défaut
     */
    private void simulateComponentReception() {
        if (!simulationMode) {
            return; // Ne rien faire si mode simulation désactivé
        }

        Random random = new Random();
        int count = 0;

        for (String type : storageZones.keySet()) {
            if (random.nextInt(100) < 30) {
                Component component = new Component("SIM-" + (++count), type, "SIMULATED");
                addComponent(component);
            }
        }
    }

    @Override
    public void run() {
        printHeader("STATION " + stationId + " OPÉRATIONNELLE");
        System.out.println("Zones de stockage: " + storageZones.keySet());
        System.out.println("Capacité max par zone: " + maxCapacity);
        printDivider();

        // CORRECTION: Envoyer les alertes initiales pour zones vides
        printInfo("📊 État initial:");
        displayStorageStatus();
        printInfo("\n💡 En attente de composants des machines...\n");
        checkStorageLevels();

        int cycleCount = 0;

        while (isRunning) {
            try {
                cycleCount++;

                // CORRECTION: Simulation désactivée par défaut
                if (simulationMode) {
                    simulateComponentReception();
                }

                // Tenter d'assembler un produit
                Product product = assembleProduct();
                if (product != null) {
                    printDivider();
                    printSuccess("✅ PRODUIT ASSEMBLÉ: " + product.getProductId());
                    printInfo("   Composants: " + product.getComponents().size());
                    printDivider();
                    displayStorageStatus();

                    // Vérifier les niveaux après assemblage
                    checkStorageLevels();
                }

                // Afficher le statut toutes les 5 itérations seulement si pas en simulation
                if (!simulationMode && cycleCount % 5 == 0) {
                    displayStorageStatus();
                }

                // Attendre avant la prochaine itération
                Thread.sleep(3000);

            } catch (InterruptedException e) {
                printWarning("Station interrompue");
                break;
            } catch (Exception e) {
                printError("Erreur station: " + e.getMessage());
                e.printStackTrace();
            }
        }

        printDivider();
        printInfo("🏁 Station " + stationId + " arrêtée");
        printInfo("📊 Total assemblé: " + assembledProducts + " produits");
        printDivider();
    }

    private void displayStorageStatus() {
        System.out.println("\n┌─────────────────────────────────────────────────┐");
        System.out.println("│         ÉTAT DES ZONES DE STOCKAGE              │");
        System.out.println("├─────────────────────────────────────────────────┤");

        for (Map.Entry<String, Queue<Component>> entry : storageZones.entrySet()) {
            int level = entry.getValue().size();
            String bar = generateBar(level, maxCapacity);
            String status = getStatusIcon(level);

            String zoneName = String.format("%-10s", entry.getKey());
            String levelStr = String.format("%2d/%2d", level, maxCapacity);

            System.out.println("│ " + status + " " + zoneName + " " + bar + " " + levelStr + "   │");
        }

        System.out.println("├─────────────────────────────────────────────────┤");
        System.out.println("│ 🏭 Produits assemblés: " + String.format("%-22d", assembledProducts) + " │");
        System.out.println("└─────────────────────────────────────────────────┘");
    }

    private String getStatusIcon(int level) {
        if (level == 0) return "🔴";
        if (level <= minCapacity) return "🟡";
        if (level >= maxCapacity) return "🔴";
        return "🟢";
    }

    private String generateBar(int current, int max) {
        int bars = (current * 20) / max;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 20; i++) {
            if (i < bars) {
                sb.append("█");
            } else {
                sb.append("░");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    public void shutdown() {
        isRunning = false;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void toggleSimulationMode() {
        simulationMode = !simulationMode;
        if (simulationMode) {
            printInfo("🔄 Mode simulation ACTIVÉ");
        } else {
            printInfo("🔄 Mode simulation DÉSACTIVÉ");
        }
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
        System.out.println("║      CONFIGURATION STATION D'ASSEMBLAGE          ║");
        System.out.println("╚═══════════════════════════════════════════════════╝\n");

        System.out.print("ID Station (ex: STATION1): ");
        String stationId = scanner.nextLine();

        System.out.print("Types de composants (séparés par virgule, ex: TYPE_A,TYPE_B): ");
        String typesStr = scanner.nextLine();
        String[] types = typesStr.split(",");

        for (int i = 0; i < types.length; i++) {
            types[i] = types[i].trim();
        }

        AssemblyStation station = new AssemblyStation(stationId, types);

        if (!station.connectToController()) {
            System.err.println("❌ Impossible de se connecter au contrôleur");
            System.err.println("💡 Assurez-vous que le serveur est démarré");
            return;
        }

        Thread stationThread = new Thread(station);
        stationThread.start();

        System.out.println("\n╔═══════════════════════════════════════════════════╗");
        System.out.println("║           MENU STATION " + stationId + "                    ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.println("║  1. 📊 Afficher statut                            ║");
        System.out.println("║  2. 📦 Ajouter composant manuellement             ║");
        System.out.println("║  3. 🔧 Assembler produit                          ║");
        System.out.println("║  4. 🔄 Activer/désactiver mode simulation         ║");
        System.out.println("║  5. 🚪 Quitter                                     ║");
        System.out.println("╚═══════════════════════════════════════════════════╝\n");

        boolean quit = false;
        while (!quit) {
            System.out.print("Choix: ");
            String choice = scanner.nextLine();

            switch (choice) {
                case "1":
                    station.displayStorageStatus();
                    break;

                case "2":
                    System.out.print("Type de composant (" + String.join(", ", types) + "): ");
                    String type = scanner.nextLine().trim();
                    if (station.storageZones.containsKey(type)) {
                        Component comp = new Component("MANUAL-" + System.currentTimeMillis(),
                                type, "MANUAL");
                        if (station.addComponent(comp)) {
                            printSuccess("✓ Composant ajouté");
                        } else {
                            System.out.println("✗ Zone pleine");
                        }
                    } else {
                        System.out.println("❌ Type invalide");
                    }
                    break;

                case "3":
                    Product product = station.assembleProduct();
                    if (product != null) {
                        printSuccess("✓ Produit assemblé: " + product.getProductId());
                        station.displayStorageStatus();
                    } else {
                        System.out.println("❌ Composants insuffisants");
                    }
                    break;

                case "4":
                    station.toggleSimulationMode();
                    break;

                case "5":
                    station.shutdown();
                    quit = true;
                    System.out.println("\n👋 Arrêt de la station " + stationId);
                    break;

                default:
                    System.out.println("❌ Choix invalide");
            }
        }

        scanner.close();
        System.exit(0);
    }

    private static void printSuccess(String msg) {
        System.out.println("✓ " + msg);
    }
}