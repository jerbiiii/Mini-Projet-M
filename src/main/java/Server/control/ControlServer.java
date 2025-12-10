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

public class ControlServer {
    private static final int SOCKET_PORT = 5000;
    private static final String CORBA_HOST = "localhost";
    private static final int CORBA_PORT = 1050;

    private ORB orb;
    private ProductionControlServant productionControlServant;
    private StationControlServant stationControlServant;
    private ServerSocket serverSocket;
    private ExecutorService threadPool;

    private final Map<String, MachineInfo> machineRegistry = new ConcurrentHashMap<>();
    private final Map<String, String> assemblyStationRegistry = new ConcurrentHashMap<>();
    private final Map<String, Integer> storageStatus = new ConcurrentHashMap<>();
    private final Map<String, Socket> activeConnections = new ConcurrentHashMap<>();
    private final Object productionLock = new Object();

    public ControlServer() {
        this.threadPool = Executors.newFixedThreadPool(20);
    }

    public void startCORBAServer(String[] args) {
        try {
            printHeader("DÉMARRAGE SERVEUR CORBA");

            Properties props = new Properties();
            props.put("org.omg.CORBA.ORBInitialHost", CORBA_HOST);
            props.put("org.omg.CORBA.ORBInitialPort", String.valueOf(CORBA_PORT));
            orb = ORB.init(args, props);

            POA rootPOA = POAHelper.narrow(orb.resolve_initial_references("RootPOA"));
            rootPOA.the_POAManager().activate();

            productionControlServant = new ProductionControlServant(this);
            stationControlServant = new StationControlServant(this);

            org.omg.CORBA.Object refProduction = rootPOA.servant_to_reference(productionControlServant);
            IProductionControl productionRef = IProductionControlHelper.narrow(refProduction);

            org.omg.CORBA.Object refStation = rootPOA.servant_to_reference(stationControlServant);
            IStationControl stationRef = IStationControlHelper.narrow(refStation);

            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);

            NameComponent[] pathProduction = ncRef.to_name("ProductionControl");
            ncRef.rebind(pathProduction, productionRef);

            NameComponent[] pathStation = ncRef.to_name("StationControl");
            ncRef.rebind(pathStation, stationRef);

            printSuccess("Serveur CORBA opérationnel (Port: " + CORBA_PORT + ")");

            Thread orbThread = new Thread(() -> orb.run());
            orbThread.start();

        } catch (Exception e) {
            printError("Erreur CORBA: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void startSocketServer() {
        try {
            printHeader("DÉMARRAGE SERVEUR SOCKET");
            serverSocket = new ServerSocket(SOCKET_PORT);
            printSuccess("Serveur Socket opérationnel (Port: " + SOCKET_PORT + ")");
            printDivider();
            printInfo("🎛️  SYSTÈME PRÊT - En attente de connexions...\n");

            while (!serverSocket.isClosed()) {
                Socket clientSocket = serverSocket.accept();
                threadPool.execute(new ClientHandler(clientSocket));
            }
        } catch (IOException e) {
            if (!serverSocket.isClosed()) {
                printError("Erreur Socket: " + e.getMessage());
            }
        }
    }

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
                // Connexion fermée
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

    private void handleSocketMessage(Message message, PrintWriter out) {
        synchronized (productionLock) {
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

    public String handleMachineFailure(String machineId, String errorType) {
        synchronized (productionLock) {
            printDivider();
            printWarning("⚠️  PANNE DÉTECTÉE");
            System.out.println("   Machine: " + machineId);
            System.out.println("   Erreur: " + errorType);

            MachineInfo failedMachine = machineRegistry.get(machineId);
            if (failedMachine == null) {
                return "ERROR: Machine non enregistrée";
            }

            failedMachine.setStatus(MachineStatus.FAILED);
            failedMachine.setLastError(errorType);

            String replacementId = findReplacementMachine(failedMachine.getMachineType(), machineId);

            if (replacementId != null) {
                printInfo("   ✓ Solution: Remplacement par " + replacementId);
                stopMachine(machineId);
                startMachine(replacementId);
                printDivider();
                return "REPLACED_BY:" + replacementId;
            } else {
                printInfo("   ✗ Aucun remplacement disponible");
                stopMachine(machineId);
                printDivider();
                return "NO_REPLACEMENT";
            }
        }
    }

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

    public void handleStorageAlert(String zoneId, String levelStr) {
        int level = Integer.parseInt(levelStr);
        storageStatus.put(zoneId, level);

        printDivider();
        printWarning("📦 ALERTE STOCKAGE");
        System.out.println("   Zone: " + zoneId);
        System.out.println("   Niveau: " + level + "%");

        if (level == 0) {
            printInfo("   → Action: Démarrage production pour " + zoneId);
            startProductionForZone(zoneId);
        } else if (level >= 100) {
            printInfo("   → Action: Arrêt production pour " + zoneId);
            stopProductionForZone(zoneId);
        }
        printDivider();
    }

    private void startProductionForZone(String zoneId) {
        for (MachineInfo machine : machineRegistry.values()) {
            if (machine.getMachineType().equals(zoneId) &&
                    machine.getStatus() == MachineStatus.STOPPED) {
                startMachine(machine.getMachineId());
                break;
            }
        }
    }

    private void stopProductionForZone(String zoneId) {
        for (MachineInfo machine : machineRegistry.values()) {
            if (machine.getMachineType().equals(zoneId) &&
                    machine.getStatus() == MachineStatus.RUNNING) {
                stopMachine(machine.getMachineId());
            }
        }
    }

    public boolean startMachine(String machineId) {
        MachineInfo machine = machineRegistry.get(machineId);
        if (machine != null && machine.getStatus() != MachineStatus.FAILED) {
            machine.setStatus(MachineStatus.RUNNING);
            printSuccess("▶️  Machine " + machineId + " démarrée");
            return true;
        }
        return false;
    }

    public boolean stopMachine(String machineId) {
        MachineInfo machine = machineRegistry.get(machineId);
        if (machine != null) {
            machine.setStatus(MachineStatus.STOPPED);
            printSuccess("⏹️  Machine " + machineId + " arrêtée");
            return true;
        }
        return false;
    }

    private void handleQualityIssue(String details) {
        printWarning("⚠️  PROBLÈME QUALITÉ: " + details);
    }

    private String getSystemStatus() {
        StringBuilder status = new StringBuilder();
        status.append("\n=== ÉTAT DU SYSTÈME ===\n");
        status.append("Machines: ").append(machineRegistry.size()).append("\n");
        for (MachineInfo machine : machineRegistry.values()) {
            status.append("  - ").append(machine).append("\n");
        }
        return status.toString();
    }

    // Getters
    public Map<String, MachineInfo> getMachineRegistry() { return machineRegistry; }
    public Map<String, String> getAssemblyStationRegistry() { return assemblyStationRegistry; }
    public Map<String, Integer> getStorageStatus() { return storageStatus; }

    public void shutdown() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            threadPool.shutdown();
            if (orb != null) {
                orb.shutdown(false);
            }
            printSuccess("\n✓ Serveur arrêté proprement");
        } catch (Exception e) {
            e.printStackTrace();
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

    private void printSuccess(String msg) {
        System.out.println("✓ " + msg);
    }

    private void printWarning(String msg) {
        System.out.println("\n" + msg);
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
        ControlServer server = new ControlServer();

        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║     SYSTÈME DE CONTRÔLE DE PRODUCTION v2.0       ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");

        server.startCORBAServer(args);

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        server.startSocketServer();

        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
    }
}