package Server.control;

import ProductionControlModule.IStationControlPOA;

/**
 * Implémentation CORBA de l'interface IStationControl
 * Gère les stations d'assemblage
 */
public class StationControlServant extends IStationControlPOA {

    private ControlServer controlServer;

    public StationControlServant(ControlServer controlServer) {
        this.controlServer = controlServer;
    }

    @Override
    public boolean registerAssemblyStation(String stationId) {
        System.out.println("\n[CORBA] Enregistrement station d'assemblage: " + stationId);

        controlServer.getAssemblyStationRegistry().put(stationId, "ACTIVE");

        System.out.println("✓ Station " + stationId + " enregistrée avec succès");
        return true;
    }

    @Override
    public void notifyQualityIssue(String details) {
        System.out.println("\n[CORBA] Problème qualité: " + details);
        // Logique de gestion de la qualité
    }

    @Override
    public boolean requestMaintenance(String machineId) {
        System.out.println("\n[CORBA] Demande de maintenance: " + machineId);

        // Arrêter la machine pour maintenance
        return controlServer.stopMachine(machineId);
    }

    @Override
    public String getProductionCommand(String stationId) {
        // Retourner les commandes de production pour la station
        if (controlServer.getAssemblyStationRegistry().containsKey(stationId)) {
            return "CONTINUE_PRODUCTION";
        }
        return "NO_COMMAND";
    }
}