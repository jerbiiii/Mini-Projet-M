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

/**
 * Station d'Assemblage - Version avec Recette de Production
 * Assemble des robots selon une recette définie:
 * - 2 BRAS
 * - 2 JAMBES
 * - 1 TETE
 * - 1 CARTE
 */
public class AssemblyStation {
    private String stationId;
    private String[] types;
    private Map<String, Queue<Component>> zones;
    private IProductionControl controlRef;
    private ORB orb;

    // NOUVEAU: Recette de production du robot
    private Map<String, Integer> productRecipe;

    private int maxCapacity = 10;
    private int minCapacity = 2;
    private int assembledCount = 0;

    // Thread séparé pour l'assemblage automatique
    private ScheduledExecutorService assemblyThread;
    // Thread pour surveillance CONTINUE des niveaux
    private ScheduledExecutorService monitoringThread;
    private volatile boolean isRunning = true;

    public AssemblyStation(String stationId, String[] types) {
        this.stationId = stationId;
        this.types = types;
        this.zones = new ConcurrentHashMap<>();

        for (String type : types) {
            zones.put(type, new ConcurrentLinkedQueue<>());
        }

        // NOUVEAU: Définir la recette du robot
        initializeProductRecipe();

        this.assemblyThread = Executors.newSingleThreadScheduledExecutor();
        this.monitoringThread = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * NOUVEAU: Initialiser la recette de production
     * Définit combien de composants de chaque type sont nécessaires
     */
    private void initializeProductRecipe() {
        productRecipe = new HashMap<>();
        productRecipe.put("TYPE_BRAS", 2);      // 2 bras
        productRecipe.put("TYPE_JAMBE", 2);     // 2 jambes
        productRecipe.put("TYPE_TETE", 1);      // 1 tête
        productRecipe.put("TYPE_CARTE", 1);     // 1 carte électronique
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

                // Démarrer ORB dans un thread séparé
                Thread orbThread = new Thread(() -> orb.run(), "ORB-Thread");
                orbThread.setDaemon(true);
                orbThread.start();

                // Afficher la recette
                displayRecipe();

                // Démarrer l'assemblage automatique
                startAssemblyLoop();

                // Surveillance CONTINUE des niveaux
                startContinuousMonitoring();

                divider();
                log("");
                printStatus();
                log("");
                info("💡 Station en mode automatique");
                info("   Assemblage: toutes les 3 secondes");
                info("   Surveillance: toutes les 5 secondes");
                info("   Prêt à recevoir des composants\n");

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
     * NOUVEAU: Afficher la recette de production
     */
    private void displayRecipe() {
        divider();
        log("🤖 RECETTE DE PRODUCTION - ROBOT");
        divider();
        for (Map.Entry<String, Integer> entry : productRecipe.entrySet()) {
            String type = entry.getKey().replace("TYPE_", "");
            int qty = entry.getValue();
            log("   • " + qty + " × " + type);
        }
        divider();
    }

    /**
     * Surveillance CONTINUE des niveaux
     */
    private void startContinuousMonitoring() {
        monitoringThread.scheduleAtFixedRate(() -> {
            try {
                checkLevelsAndAlert();
            } catch (Exception e) {
                // Continuer la surveillance même en cas d'erreur
            }
        }, 2, 5, TimeUnit.SECONDS);
    }

    /**
     * Boucle d'assemblage automatique
     */
    private void startAssemblyLoop() {
        assemblyThread.scheduleAtFixedRate(() -> {
            try {
                Product product = tryAssemble();
                if (product != null) {
                    divider();
                    success("✅ ROBOT ASSEMBLÉ: " + product.getProductId());
                    info("   Composants utilisés: " + product.getComponents().size());

                    // Afficher les détails des composants utilisés
                    Map<String, Integer> usedComponents = new HashMap<>();
                    for (Component c : product.getComponents()) {
                        String type = c.getType().replace("TYPE_", "");
                        usedComponents.put(type, usedComponents.getOrDefault(type, 0) + 1);
                    }

                    for (Map.Entry<String, Integer> entry : usedComponents.entrySet()) {
                        info("      - " + entry.getValue() + " × " + entry.getKey());
                    }

                    divider();
                    printStatus();

                    // Vérifier immédiatement après assemblage
                    checkLevelsAndAlert();
                }
            } catch (Exception e) {
                // Continuer même en cas d'erreur
            }
        }, 3, 3, TimeUnit.SECONDS);
    }

    /**
     * Réception de composant depuis le serveur (callback CORBA)
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

        // Vérifier immédiatement les niveaux après réception
        checkLevelsAndAlert();

        return true;
    }

    /**
     * Vérifier les niveaux de stockage et alerter le serveur
     */
    private void checkLevelsAndAlert() {
        for (Map.Entry<String, Queue<Component>> entry : zones.entrySet()) {
            String zoneId = entry.getKey();
            int level = entry.getValue().size();

            try {
                if (level == 0) {
                    controlRef.notifyStorageAlert(zoneId, 0);
                }
                else if (level >= maxCapacity) {
                    controlRef.notifyStorageAlert(zoneId, 100);
                }
                else if (level <= minCapacity) {
                    int percentage = (level * 100) / maxCapacity;
                    controlRef.notifyStorageAlert(zoneId, percentage);
                }

            } catch (Exception e) {
                // Ignorer les erreurs temporaires
            }
        }
    }

    /**
     * MODIFIÉ: Tenter d'assembler un robot selon la recette
     * Vérifie qu'on a suffisamment de composants de chaque type
     */
    private synchronized Product tryAssemble() {
        // Vérifier qu'on a suffisamment de composants pour CHAQUE type selon la recette
        for (Map.Entry<String, Integer> recipeEntry : productRecipe.entrySet()) {
            String type = recipeEntry.getKey();
            int required = recipeEntry.getValue();

            Queue<Component> zone = zones.get(type);
            if (zone == null || zone.size() < required) {
                // Pas assez de composants de ce type
                return null;
            }
        }

        // On a tous les composants nécessaires! Créer le robot
        String productId = stationId + "-ROBOT" + (++assembledCount);
        Product product = new Product(productId, getTotalComponentsNeeded());

        // Retirer les composants selon la recette
        for (Map.Entry<String, Integer> recipeEntry : productRecipe.entrySet()) {
            String type = recipeEntry.getKey();
            int required = recipeEntry.getValue();

            Queue<Component> zone = zones.get(type);

            // Retirer le nombre requis de composants
            for (int i = 0; i < required; i++) {
                Component comp = zone.poll();
                if (comp != null) {
                    product.addComponent(comp);
                }
            }
        }

        return product;
    }

    /**
     * NOUVEAU: Calculer le nombre total de composants nécessaires
     */
    private int getTotalComponentsNeeded() {
        int total = 0;
        for (int qty : productRecipe.values()) {
            total += qty;
        }
        return total;
    }

    /**
     * MODIFIÉ: Afficher l'état avec les quantités nécessaires
     */
    private void printStatus() {
        log("┌─────────────────────────────────────────────────┐");
        log("│           ZONES DE STOCKAGE                     │");
        log("├─────────────────────────────────────────────────┤");

        for (Map.Entry<String, Queue<Component>> entry : zones.entrySet()) {
            String type = entry.getKey();
            int level = entry.getValue().size();
            int required = productRecipe.getOrDefault(type, 1);

            String icon = getIcon(level, required);
            String bar = generateBar(level);
            String name = String.format("%-12s", type.replace("TYPE_", ""));
            String count = String.format("%2d/%2d", level, maxCapacity);
            String need = String.format("(besoin:%d)", required);

            log(String.format("│ %s %s %s %s %-10s │", icon, name, bar, count, need));
        }

        log("├─────────────────────────────────────────────────┤");
        log(String.format("│ 🤖 Robots assemblés: %-26d │", assembledCount));

        // NOUVEAU: Afficher si on peut assembler un robot
        if (canAssemble()) {
            log("│ ✅ Prêt à assembler un robot!                   │");
        } else {
            log("│ ⏳ En attente de composants...                  │");
        }

        log("└─────────────────────────────────────────────────┘");
    }

    /**
     * NOUVEAU: Vérifier si on peut assembler un robot
     */
    private boolean canAssemble() {
        for (Map.Entry<String, Integer> recipeEntry : productRecipe.entrySet()) {
            String type = recipeEntry.getKey();
            int required = recipeEntry.getValue();

            Queue<Component> zone = zones.get(type);
            if (zone == null || zone.size() < required) {
                return false;
            }
        }
        return true;
    }

    /**
     * MODIFIÉ: Icône selon le niveau ET les besoins
     */
    private String getIcon(int level, int required) {
        if (level == 0) return "🔴"; // Vide
        if (level >= maxCapacity) return "🔴"; // Pleine
        if (level < required) return "🟡"; // Insuffisant pour assembler
        return "🟢"; // OK
    }

    /**
     * Barre de progression visuelle
     */
    private String generateBar(int level) {
        int filled = (level * 12) / maxCapacity;
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < 12; i++) {
            bar.append(i < filled ? "█" : "░");
        }
        bar.append("]");
        return bar.toString();
    }

    public void shutdown() {
        isRunning = false;
        assemblyThread.shutdown();
        monitoringThread.shutdown();
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
        System.out.println("║    CONFIGURATION STATION D'ASSEMBLAGE ROBOT      ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
        System.out.println();

        System.out.print("ID Station (ex: STATION1): ");
        String id = sc.nextLine().trim();

        System.out.println("\n💡 Types nécessaires pour assembler un robot:");
        System.out.println("   TYPE_BRAS, TYPE_JAMBE, TYPE_TETE, TYPE_CARTE");
        System.out.print("\nTypes de composants (séparés par virgule): ");
        String typesStr = sc.nextLine().trim();

        // Si vide, utiliser les types par défaut
        if (typesStr.isEmpty()) {
            typesStr = "TYPE_BRAS,TYPE_JAMBE,TYPE_TETE,TYPE_CARTE";
            System.out.println("→ Types par défaut utilisés");
        }

        String[] types = typesStr.split(",");
        for (int i = 0; i < types.length; i++) {
            types[i] = types[i].trim();
        }

        AssemblyStation station = new AssemblyStation(id, types);

        if (!station.connect()) {
            System.exit(1);
        }

        // Menu simplifié
        System.out.println("\n╔═══════════════════════════════════════════════════╗");
        System.out.println("║         MENU STATION (MODE AUTO)                 ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.println("║  1. Afficher état                                ║");
        System.out.println("║  2. Quitter                                      ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("🤖 Assemblage automatique de robots");
        System.out.println("   Recette: 2 bras + 2 jambes + 1 tête + 1 carte\n");

        while (station.isRunning) {
            System.out.print(id + " > ");
            String choice = sc.nextLine().trim();

            switch (choice) {
                case "1":
                    station.printStatus();
                    break;

                case "2":
                    System.out.println("\n👋 Arrêt de la station " + id);
                    station.shutdown();
                    System.exit(0);
                    break;

                default:
                    System.out.println("❌ Commande invalide (1 ou 2)");
            }
        }
    }
}