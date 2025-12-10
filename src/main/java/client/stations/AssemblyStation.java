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

/**
 * Station d'assemblage (Client Socket + CORBA)
 * Communique avec le contrôleur via Socket pour les alertes de stockage
 * et via CORBA pour l'enregistrement
 */
public class AssemblyStation implements Runnable {

    private String stationId;
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private IStationControl stationControlRef;

    // Zones de stockage (une file par type de composant)
    private Map<String, Queue<Component>> storageZones;
    private int maxCapacity = 10; // capacité maximale par zone
    private int minCapacity = 2;  // seuil minimum avant alerte

    // Statistiques
    private int assembledProducts = 0;
    private boolean isRunning = true;

    // Configuration réseau
    private static final String SERVER_HOST = "localhost";
    private static final int SOCKET_PORT = 5000;
    private static final String CORBA_HOST = "localhost";
    private static final int CORBA_PORT = 1050;

    public AssemblyStation(String stationId, String[] componentTypes) {
        this.stationId = stationId;
        this.storageZones = new ConcurrentHashMap<>();

        // Initialiser les zones de stockage
        for (String type : componentTypes) {
            storageZones.put(type, new ConcurrentLinkedQueue<>());
        }
    }

    /**
     * Connexion au contrôleur (Socket + CORBA)
     */
    public boolean connectToController() {
        try {
            // Connexion CORBA pour l'enregistrement
            System.out.println("\n=== Connexion CORBA au contrôleur ===");
            Properties props = new Properties();
            props.put("org.omg.CORBA.ORBInitialHost", CORBA_HOST);
            props.put("org.omg.CORBA.ORBInitialPort", String.valueOf(CORBA_PORT));

            String[] args = new String[0];
            ORB orb = ORB.init(args, props);

            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);

            String name = "StationControl";
            stationControlRef = IStationControlHelper.narrow(ncRef.resolve_str(name));

            System.out.println("✓ Connecté au service StationControl");

            // Enregistrer la station
            boolean registered = stationControlRef.registerAssemblyStation(stationId);
            if (registered) {
                System.out.println("✓ Station " + stationId + " enregistrée");
            }

            // Connexion Socket pour les alertes
            System.out.println("\n=== Connexion Socket au contrôleur ===");
            socket = new Socket(SERVER_HOST, SOCKET_PORT);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            System.out.println("✓ Connecté au serveur Socket sur port " + SOCKET_PORT);

            return true;

        } catch (Exception e) {
            System.err.println("✗ Erreur de connexion: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Vérifier les niveaux de stockage
     */
    public void checkStorageLevels() {
        for (Map.Entry<String, Queue<Component>> entry : storageZones.entrySet()) {
            String zoneId = entry.getKey();
            int level = entry.getValue().size();

            // Alerte si zone vide
            if (level == 0) {
                System.out.println("\n⚠ ALERTE: Zone " + zoneId + " VIDE");
                sendStorageAlert(zoneId, 0);
            }
            // Alerte si zone pleine
            else if (level >= maxCapacity) {
                System.out.println("\n⚠ ALERTE: Zone " + zoneId + " PLEINE (" + level + ")");
                sendStorageAlert(zoneId, 100);
            }
            // Alerte si niveau bas
            else if (level <= minCapacity) {
                System.out.println("\n⚠ ALERTE: Zone " + zoneId + " NIVEAU BAS (" + level + ")");
                sendStorageAlert(zoneId, level * 10);
            }
        }
    }

    /**
     * Envoyer une alerte de stockage via Socket
     */
    public void sendStorageAlert(String zoneId, int level) {
        if (out != null) {
            Message alert = new Message(Message.TYPE_STORAGE_ALERT, stationId,
                    zoneId + ":" + level);
            out.println(alert.serialize());

            try {
                String response = in.readLine();
                System.out.println("→ Réponse contrôleur: " + response);
            } catch (IOException e) {
                System.err.println("Erreur lecture réponse: " + e.getMessage());
            }
        }
    }

    /**
     * Ajouter un composant à une zone de stockage
     */
    public boolean addComponent(Component component) {
        Queue<Component> zone = storageZones.get(component.getType());
        if (zone != null && zone.size() < maxCapacity) {
            zone.offer(component);
            System.out.println("✓ Composant ajouté: " + component +
                    " (Zone: " + component.getType() + ", Niveau: " + zone.size() + ")");
            return true;
        }
        return false;
    }

    /**
     * Assembler un produit
     */
    public Product assembleProduct() {
        // Vérifier si on a au moins un composant de chaque type
        boolean canAssemble = true;
        for (Queue<Component> zone : storageZones.values()) {
            if (zone.isEmpty()) {
                canAssemble = false;
                break;
            }
        }

        if (!canAssemble) {
            return null;
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
     * Simuler la réception de composants
     */
    private void simulateComponentReception() {
        Random random = new Random();
        int count = 0;

        for (String type : storageZones.keySet()) {
            if (random.nextInt(100) < 30) { // 30% de chance de recevoir un composant
                Component component = new Component("SIM-" + (++count), type, "SIMULATED");
                addComponent(component);
            }
        }
    }

    /**
     * Boucle principale de la station
     */
    @Override
    public void run() {
        System.out.println("\n" + stationId + " en fonctionnement");
        System.out.println("Zones de stockage: " + storageZones.keySet());

        while (isRunning) {
            try {
                // Simuler la réception de composants
                simulateComponentReception();

                // Vérifier les niveaux de stockage
                checkStorageLevels();

                // Tenter d'assembler un produit
                Product product = assembleProduct();
                if (product != null) {
                    System.out.println("\n✓ PRODUIT ASSEMBLÉ: " + product);
                }

                // Afficher l'état des zones
                displayStorageStatus();

                // Attendre avant la prochaine itération
                Thread.sleep(5000);

            } catch (InterruptedException e) {
                System.err.println("Station interrompue");
                break;
            } catch (Exception e) {
                System.err.println("Erreur station: " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println(stationId + " arrêtée. Total assemblé: " + assembledProducts);
    }

    /**
     * Afficher le statut du stockage
     */
    private void displayStorageStatus() {
        System.out.println("\n--- Statut Stockage " + stationId + " ---");
        for (Map.Entry<String, Queue<Component>> entry : storageZones.entrySet()) {
            int level = entry.getValue().size();
            String bar = generateBar(level, maxCapacity);
            System.out.println(entry.getKey() + ": " + bar + " " + level + "/" + maxCapacity);
        }
        System.out.println("Produits assemblés: " + assembledProducts);
    }

    /**
     * Générer une barre de progression
     */
    private String generateBar(int current, int max) {
        int bars = (current * 10) / max;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            sb.append(i < bars ? "█" : "░");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Arrêter la station
     */
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

    /**
     * Main - Lancement de la station
     */
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("=== Configuration Station d'Assemblage ===");
        System.out.print("ID Station (ex: STATION1): ");
        String stationId = scanner.nextLine();

        System.out.print("Types de composants (séparés par virgule, ex: TYPE_A,TYPE_B,TYPE_C): ");
        String typesStr = scanner.nextLine();
        String[] types = typesStr.split(",");

        // Nettoyer les types
        for (int i = 0; i < types.length; i++) {
            types[i] = types[i].trim();
        }

        AssemblyStation station = new AssemblyStation(stationId, types);

        // Connexion au contrôleur
        if (!station.connectToController()) {
            System.err.println("Impossible de se connecter au contrôleur");
            return;
        }

        // Démarrer le thread de la station
        Thread stationThread = new Thread(station);
        stationThread.start();

        // Menu interactif
        System.out.println("\n=== Menu Station " + stationId + " ===");
        System.out.println("1. Afficher statut");
        System.out.println("2. Ajouter composant manuellement");
        System.out.println("3. Assembler produit");
        System.out.println("4. Quitter");

        boolean quit = false;
        while (!quit) {
            System.out.print("\nChoix: ");
            String choice = scanner.nextLine();

            switch (choice) {
                case "1":
                    station.displayStorageStatus();
                    break;

                case "2":
                    System.out.print("Type de composant: ");
                    String type = scanner.nextLine();
                    Component comp = new Component("MANUAL-" + System.currentTimeMillis(),
                            type, "MANUAL");
                    if (station.addComponent(comp)) {
                        System.out.println("✓ Composant ajouté");
                    } else {
                        System.out.println("✗ Zone pleine ou type invalide");
                    }
                    break;

                case "3":
                    Product product = station.assembleProduct();
                    if (product != null) {
                        System.out.println("✓ Produit assemblé: " + product);
                    } else {
                        System.out.println("✗ Composants insuffisants");
                    }
                    break;

                case "4":
                    station.shutdown();
                    quit = true;
                    break;

                default:
                    System.out.println("Choix invalide");
            }
        }

        scanner.close();
        System.out.println("Station " + stationId + " terminée");
        System.exit(0);
    }
}