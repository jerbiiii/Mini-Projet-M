package Server.control;

import common.MachineInfo;
import common.MachineInfo.MachineStatus;
import common.Message;
import org.omg.CORBA.ORB;
import org.omg.CosNaming.NameComponent;
import org.omg.CosNaming.NamingContextExt;
import org.omg.CosNaming.NamingContextExtHelper;
import org.omg.PortableServer.POA;
import org.omg.PortableServer.POAHelper;
import ProductionControlModule.*;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;

/**
 * Serveur de contrôle principal (Multi-Thread Server)
 * Gère les machines de production et la station d'assemblage
 * Utilise à la fois CORBA et Sockets
 */
public class ControlServer {

    // Configuration réseau
    private static final int SOCKET_PORT = 5000;
    private static final String CORBA_HOST = "localhost";
    private static final int CORBA_PORT = 1050;

    // Composants CORBA
    private ORB orb;
    private ProductionControlServant productionControlServant;
    private StationControlServant stationControlServant;

    // Composants Sockets
    private ServerSocket serverSocket;
    private ExecutorService threadPool;

    // Registres des machines et stations (avec synchronisation)
    private final Map<String, MachineInfo> machineRegistry = new ConcurrentHashMap<>();
    private final Map<String, String> assemblyStationRegistry = new ConcurrentHashMap<>();
    private final Map<String, Integer> storageStatus = new ConcurrentHashMap<>();

    // Gestion des connexions actives
    private final Map<String, Socket> activeConnections = new ConcurrentHashMap<>();

    // Verrou pour la synchronisation
    private final Object productionLock = new Object();

    public ControlServer() {
        this.threadPool = Executors.newFixedThreadPool(20);
    }

    /**
     * Démarre le serveur CORBA
     */
    public void startCORBAServer(String[] args) {
        try {
            System.out.println("=== Démarrage du serveur CORBA ===");

            // Créer et initialiser l'ORB
            Properties props = new Properties();
            props.put("org.omg.CORBA.ORBInitialHost", CORBA_HOST);
            props.put("org.omg.CORBA.ORBInitialPort", String.valueOf(CORBA_PORT));

            orb = ORB.init(args, props);

            // Obtenir la référence au POA
            POA rootPOA = POAHelper.narrow(orb.resolve_initial_references("RootPOA"));
            rootPOA.the_POAManager().activate();

            // Créer les servants
            productionControlServant = new ProductionControlServant(this);
            stationControlServant = new StationControlServant(this);

            // Obtenir les références objets
            org.omg.CORBA.Object refProduction = rootPOA.servant_to_reference(productionControlServant);
            IProductionControl productionRef = IProductionControlHelper.narrow(refProduction);

            org.omg.CORBA.Object refStation = rootPOA.servant_to_reference(stationControlServant);
            IStationControl stationRef = IStationControlHelper.narrow(refStation);

            // Obtenir le contexte de nommage
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);

            // Enregistrer les objets dans le service de noms
            NameComponent[] pathProduction = ncRef.to_name("ProductionControl");
            ncRef.rebind(pathProduction, productionRef);

            NameComponent[] pathStation = ncRef.to_name("StationControl");
            ncRef.rebind(pathStation, stationRef);

            System.out.println("✓ Serveur CORBA démarré sur port " + CORBA_PORT);
            System.out.println("✓ Services enregistrés: ProductionControl, StationControl");

            // Démarrer l'ORB dans un thread séparé
            Thread orbThread = new Thread(() -> orb.run());
            orbThread.start();

        } catch (Exception e) {
            System.err.println("Erreur démarrage CORBA: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Démarre le serveur Socket (Multi-Thread)
     */
    public void startSocketServer() {
        try {
            System.out.println("\n=== Démarrage du serveur Socket ===");
            serverSocket = new ServerSocket(SOCKET_PORT);
            System.out.println("✓ Serveur Socket démarré sur port " + SOCKET_PORT);

            // Boucle d'acceptation des connexions
            while (!serverSocket.isClosed()) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("✓ Nouvelle connexion: " + clientSocket.getInetAddress());

                // Traiter chaque connexion dans un thread séparé
                threadPool.execute(new ClientHandler(clientSocket));
            }

        } catch (IOException e) {
            if (!serverSocket.isClosed()) {
                System.err.println("Erreur serveur Socket: " + e.getMessage());
            }
        }
    }

    /**
     * Gère une connexion client (Thread Handler)
     */
    private class ClientHandler implements Runnable {
        private Socket socket;
        private BufferedReader in;
        private PrintWriter out;
        private String clientId;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    Message message = Message.deserialize(inputLine);
                    if (message != null) {
                        handleSocketMessage(message, out);
                    }
                }

            } catch (IOException e) {
                System.err.println("Erreur communication client: " + e.getMessage());
            } finally {
                closeConnection();
            }
        }

        private void closeConnection() {
            try {
                if (clientId != null) {
                    activeConnections.remove(clientId);
                }
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Traite un message reçu via Socket
     */
    private void handleSocketMessage(Message message, PrintWriter out) {
        synchronized (productionLock) {
            System.out.println("\n→ Message reçu: " + message);

            String response = "OK";

            switch (message.getMessageType()) {
                case Message.TYPE_STORAGE_ALERT:
                    handleStorageAlert(message.getSenderId(), message.getContent());
                    break;

                case Message.TYPE_QUALITY_ISSUE:
                    handleQualityIssue(message.getContent());
                    break;

                case Message.TYPE_STATUS:
                    response = getSystemStatus();
                    break;

                default:
                    response = "UNKNOWN_MESSAGE_TYPE";
            }

            out.println(response);
        }
    }

    /**
     * Gère la panne d'une machine
     */
    public String handleMachineFailure(String machineId, String errorType) {
        synchronized (productionLock) {
            System.out.println("\n⚠ PANNE DÉTECTÉE: Machine " + machineId + " - " + errorType);

            MachineInfo failedMachine = machineRegistry.get(machineId);
            if (failedMachine == null) {
                return "ERROR: Machine non enregistrée";
            }

            // Marquer la machine comme en panne
            failedMachine.setStatus(MachineStatus.FAILED);
            failedMachine.setLastError(errorType);

            // Chercher une machine de remplacement du même type
            String replacementId = findReplacementMachine(failedMachine.getMachineType(), machineId);

            if (replacementId != null) {
                System.out.println("→ Solution: Arrêt de " + machineId +
                        ", remplacement par " + replacementId);

                // Arrêter la machine en panne
                stopMachine(machineId);

                // Démarrer la machine de remplacement
                startMachine(replacementId);

                return "REPLACED_BY:" + replacementId;
            } else {
                System.out.println("→ Aucun remplacement disponible pour " + machineId);
                stopMachine(machineId);
                return "NO_REPLACEMENT";
            }
        }
    }

    /**
     * Trouve une machine de remplacement
     */
    private String findReplacementMachine(String machineType, String excludeMachineId) {
        for (Map.Entry<String, MachineInfo> entry : machineRegistry.entrySet()) {
            MachineInfo machine = entry.getValue();
            if (machine.getMachineType().equals(machineType) &&
                    !machine.getMachineId().equals(excludeMachineId) &&
                    machine.getStatus() == MachineStatus.STOPPED) {
                return machine.getMachineId();
            }
        }
        return null;
    }

    /**
     * Gère une alerte de stockage
     */
    public void handleStorageAlert(String zoneId, String levelStr) {
        int level = Integer.parseInt(levelStr);
        storageStatus.put(zoneId, level);

        System.out.println("\n📦 ALERTE STOCKAGE: Zone " + zoneId + " - Niveau: " + level);

        if (level == 0) {
            System.out.println("→ Zone vide - Redémarrage de la production pour " + zoneId);
            startProductionForZone(zoneId);
        } else if (level >= 100) {
            System.out.println("→ Zone pleine - Arrêt de la production pour " + zoneId);
            stopProductionForZone(zoneId);
        }
    }

    /**
     * Démarre la production pour une zone spécifique
     */
    private void startProductionForZone(String zoneId) {
        for (MachineInfo machine : machineRegistry.values()) {
            if (machine.getMachineType().equals(zoneId) &&
                    machine.getStatus() == MachineStatus.STOPPED) {
                startMachine(machine.getMachineId());
                break;
            }
        }
    }

    /**
     * Arrête la production pour une zone spécifique
     */
    private void stopProductionForZone(String zoneId) {
        for (MachineInfo machine : machineRegistry.values()) {
            if (machine.getMachineType().equals(zoneId) &&
                    machine.getStatus() == MachineStatus.RUNNING) {
                stopMachine(machine.getMachineId());
            }
        }
    }

    /**
     * Démarre une machine
     */
    public boolean startMachine(String machineId) {
        MachineInfo machine = machineRegistry.get(machineId);
        if (machine != null && machine.getStatus() != MachineStatus.FAILED) {
            machine.setStatus(MachineStatus.RUNNING);
            System.out.println("✓ Machine " + machineId + " démarrée");
            return true;
        }
        return false;
    }

    /**
     * Arrête une machine
     */
    public boolean stopMachine(String machineId) {
        MachineInfo machine = machineRegistry.get(machineId);
        if (machine != null) {
            machine.setStatus(MachineStatus.STOPPED);
            System.out.println("✓ Machine " + machineId + " arrêtée");
            return true;
        }
        return false;
    }

    /**
     * Gère un problème de qualité
     */
    private void handleQualityIssue(String details) {
        System.out.println("\n⚠ PROBLÈME QUALITÉ: " + details);
        // Logique de gestion de la qualité
    }

    /**
     * Retourne le statut du système
     */
    private String getSystemStatus() {
        StringBuilder status = new StringBuilder();
        status.append("=== STATUT SYSTÈME ===\n");
        status.append("Machines: ").append(machineRegistry.size()).append("\n");
        for (MachineInfo machine : machineRegistry.values()) {
            status.append("  - ").append(machine).append("\n");
        }
        status.append("Zones de stockage: ").append(storageStatus.size()).append("\n");
        for (Map.Entry<String, Integer> entry : storageStatus.entrySet()) {
            status.append("  - ").append(entry.getKey())
                    .append(": ").append(entry.getValue()).append("\n");
        }
        return status.toString();
    }

    // Getters pour les registres
    public Map<String, MachineInfo> getMachineRegistry() {
        return machineRegistry;
    }

    public Map<String, String> getAssemblyStationRegistry() {
        return assemblyStationRegistry;
    }

    public Map<String, Integer> getStorageStatus() {
        return storageStatus;
    }

    /**
     * Arrête le serveur proprement
     */
    public void shutdown() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            threadPool.shutdown();
            if (orb != null) {
                orb.shutdown(false);
            }
            System.out.println("\n✓ Serveur arrêté proprement");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Main - Point d'entrée du serveur
     */
    public static void main(String[] args) {
        ControlServer server = new ControlServer();

        // Démarrer CORBA
        server.startCORBAServer(args);

        // Attendre un peu que CORBA soit complètement initialisé
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // Démarrer le serveur Socket
        server.startSocketServer();

        // Hook pour arrêt propre
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
    }
}