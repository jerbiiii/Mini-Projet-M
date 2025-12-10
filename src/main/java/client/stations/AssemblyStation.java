package client.stations;

import common.Component;
import common.Product;
import org.omg.CORBA.ORB;
import org.omg.CosNaming.NamingContextExt;
import org.omg.CosNaming.NamingContextExtHelper;
import org.omg.PortableServer.POA;
import org.omg.PortableServer.POAHelper;
import ProductionControlModule.*;

import java.util.*;
import java.util.concurrent.*;

public class AssemblyStation {
    private String stationId;
    private String[] types;
    private Map<String, Queue<Component>> zones;
    private IProductionControl controlRef;
    private ORB orb;

    private int maxCapacity = 10;
    private int minCapacity = 2;
    private int assembledCount = 0;

    // Thread séparé pour l'assemblage
    private ScheduledExecutorService assemblyThread;
    private boolean isRunning = true;

    public AssemblyStation(String stationId, String[] types) {
        this.stationId = stationId;
        this.types = types;
        this.zones = new ConcurrentHashMap<>();

        for (String type : types) {
            zones.put(type, new ConcurrentLinkedQueue<>());
        }

        // Thread d'assemblage qui s'exécute toutes les 3 secondes
        this.assemblyThread = Executors.newSingleThreadScheduledExecutor();
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

            orb = ORB.init(new String[0], props);

            POA rootPOA = POAHelper.narrow(orb.resolve_initial_references("RootPOA"));
            rootPOA.the_POAManager().activate();

            // Créer callback servant
            StationCallbackServant servant = new StationCallbackServant();
            org.omg.CORBA.Object ref = rootPOA.servant_to_reference(servant);
            IStationCallback callback = IStationCallbackHelper.narrow(ref);

            // Obtenir référence serveur
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
            controlRef = IProductionControlHelper.narrow(ncRef.resolve_str("ProductionControl"));

            // S'enregistrer
            if (controlRef.registerAssemblyStation(stationId, callback)) {
                success("✓ Station enregistrée: " + stationId);

                // Démarrer ORB dans un thread séparé (IMPORTANT!)
                Thread orbThread = new Thread(() -> orb.run(), "ORB-Thread");
                orbThread.setDaemon(true);
                orbThread.start();

                // Démarrer l'assemblage automatique
                startAssemblyLoop();

                // Envoyer alertes initiales
                checkLevelsAndAlert();

                divider();
                log("");
                printStatus();
                log("");
                info("💡 Prêt à recevoir des composants\n");

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
     * Démarrer la boucle d'assemblage automatique
     */
    private void startAssemblyLoop() {
        assemblyThread.scheduleAtFixedRate(() -> {
            try {
                Product product = tryAssemble();
                if (product != null) {
                    divider();
                    success("✅ PRODUIT ASSEMBLÉ: " + product.getProductId());
                    info("   Composants utilisés: " + product.getComponents().size());
                    divider();
                    printStatus();
                    checkLevelsAndAlert();
                }
            } catch (Exception e) {
                // Ignorer les erreurs pour continuer
            }
        }, 3, 3, TimeUnit.SECONDS);
    }

    /**
     * IMPORTANT: Méthode appelée par le serveur via CORBA
     * Ne doit JAMAIS bloquer!
     */
    public synchronized boolean receiveComponent(Component comp) {
        Queue<Component> zone = zones.get(comp.getType());

        if (zone == null) {
            warning("❌ Type inconnu: " + comp.getType());
            return false;
        }

        if (zone.size() >= maxCapacity) {
            warning("❌ Zone " + comp.getType() + " PLEINE - Rejeté");
            return false;
        }

        zone.offer(comp);

        divider();
        success("📦 COMPOSANT REÇU");
        info("   ID: " + comp.getComponentId());
        info("   Type: " + comp.getType());
        info("   De: " + comp.getProducedBy());
        divider();
        printStatus();

        checkLevelsAndAlert();

        return true;
    }

    private void checkLevelsAndAlert() {
        for (Map.Entry<String, Queue<Component>> entry : zones.entrySet()) {
            String zoneId = entry.getKey();
            int level = entry.getValue().size();

            try {
                if (level == 0) {
                    controlRef.notifyStorageAlert(zoneId, 0);
                } else if (level >= maxCapacity) {
                    controlRef.notifyStorageAlert(zoneId, 100);
                } else if (level <= minCapacity) {
                    controlRef.notifyStorageAlert(zoneId, level * 10);
                }
            } catch (Exception e) {
                // Ignorer
            }
        }
    }

    private synchronized Product tryAssemble() {
        // Vérifier qu'on a au moins un de chaque type
        for (Queue<Component> zone : zones.values()) {
            if (zone.isEmpty()) {
                return null;
            }
        }

        String productId = stationId + "-P" + (++assembledCount);
        Product product = new Product(productId, zones.size());

        for (Queue<Component> zone : zones.values()) {
            Component comp = zone.poll();
            if (comp != null) {
                product.addComponent(comp);
            }
        }

        return product;
    }

    private void printStatus() {
        log("┌─────────────────────────────────────────────────┐");
        log("│           ZONES DE STOCKAGE                     │");
        log("├─────────────────────────────────────────────────┤");

        for (Map.Entry<String, Queue<Component>> entry : zones.entrySet()) {
            int level = entry.getValue().size();
            String icon = getIcon(level);
            String bar = generateBar(level);
            String name = String.format("%-10s", entry.getKey());
            String count = String.format("%2d/%2d", level, maxCapacity);

            log(String.format("│ %s %s %s %s  │", icon, name, bar, count));
        }

        log("├─────────────────────────────────────────────────┤");
        log(String.format("│ 🏭 Produits assemblés: %-23d │", assembledCount));
        log("└─────────────────────────────────────────────────┘");
    }

    private String getIcon(int level) {
        if (level == 0) return "🔴";
        if (level >= maxCapacity) return "🔴";
        if (level <= minCapacity) return "🟡";
        return "🟢";
    }

    private String generateBar(int level) {
        int filled = (level * 15) / maxCapacity;
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < 15; i++) {
            bar.append(i < filled ? "█" : "░");
        }
        bar.append("]");
        return bar.toString();
    }

    public void shutdown() {
        isRunning = false;
        assemblyThread.shutdown();
        if (orb != null) {
            orb.shutdown(false);
        }
    }

    /**
     * Servant CORBA pour recevoir les callbacks du serveur
     */
    private class StationCallbackServant extends IStationCallbackPOA {
        @Override
        public boolean receiveComponent(ComponentData data) {
            Component comp = new Component(data.componentId, data.type, data.producedBy);
            comp.setDefective(data.isDefective);
            return AssemblyStation.this.receiveComponent(comp);
        }

        @Override
        public String getStationId() {
            return stationId;
        }
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
        System.out.println("║       CONFIGURATION STATION D'ASSEMBLAGE         ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
        System.out.println();

        System.out.print("ID Station (ex: STATION1): ");
        String id = sc.nextLine().trim();

        System.out.print("Types de composants (ex: TYPE_A,TYPE_B): ");
        String typesStr = sc.nextLine().trim();
        String[] types = typesStr.split(",");
        for (int i = 0; i < types.length; i++) {
            types[i] = types[i].trim();
        }

        AssemblyStation station = new AssemblyStation(id, types);

        if (!station.connect()) {
            System.exit(1);
        }

        // Menu interactif
        System.out.println("\n╔═══════════════════════════════════════════════════╗");
        System.out.println("║                MENU STATION                      ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.println("║  1. Afficher état                                ║");
        System.out.println("║  2. Forcer assemblage                            ║");
        System.out.println("║  3. Quitter                                      ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
        System.out.println();

        while (station.isRunning) {
            System.out.print(id + " > ");
            String choice = sc.nextLine().trim();

            switch (choice) {
                case "1":
                    station.printStatus();
                    break;

                case "2":
                    Product p = station.tryAssemble();
                    if (p != null) {
                        System.out.println("✓ Produit assemblé: " + p.getProductId());
                        station.printStatus();
                    } else {
                        System.out.println("✗ Composants manquants");
                    }
                    break;

                case "3":
                    System.out.println("\n👋 Arrêt de la station " + id);
                    station.shutdown();
                    System.exit(0);
                    break;

                default:
                    System.out.println("❌ Commande invalide (1, 2 ou 3)");
            }
        }
    }
}