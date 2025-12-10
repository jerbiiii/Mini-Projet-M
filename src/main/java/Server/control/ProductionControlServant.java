package Server.control;

import common.MachineInfo;
import common.MachineInfo.MachineStatus;
import ProductionControlModule.IProductionControlPOA;

/**
 * Implémentation CORBA de l'interface IProductionControl
 * Gère les machines de production
 */
public class ProductionControlServant extends IProductionControlPOA {

    private ControlServer controlServer;

    public ProductionControlServant(ControlServer controlServer) {
        this.controlServer = controlServer;
    }

    @Override
    public boolean registerMachine(String machineId, String machineType) {
        System.out.println("\n[CORBA] Enregistrement machine: " + machineId +
                " (Type: " + machineType + ")");

        MachineInfo machineInfo = new MachineInfo(machineId, machineType);
        controlServer.getMachineRegistry().put(machineId, machineInfo);

        System.out.println("✓ Machine " + machineId + " enregistrée avec succès");
        return true;
    }

    @Override
    public String notifyFailure(String machineId, String errorType) {
        System.out.println("\n[CORBA] Notification de panne: " + machineId +
                " - Erreur: " + errorType);

        String result = controlServer.handleMachineFailure(machineId, errorType);
        return result;
    }

    @Override
    public boolean requestProductionStart(String machineId) {
        System.out.println("\n[CORBA] Demande de démarrage: " + machineId);
        return controlServer.startMachine(machineId);
    }

    @Override
    public boolean requestProductionStop(String machineId) {
        System.out.println("\n[CORBA] Demande d'arrêt: " + machineId);
        return controlServer.stopMachine(machineId);
    }

    @Override
    public void notifyStorageAlert(String zoneId, int level) {
        System.out.println("\n[CORBA] Alerte stockage: Zone " + zoneId +
                " - Niveau: " + level);
        controlServer.handleStorageAlert(zoneId, String.valueOf(level));
    }

    @Override
    public String getMachineStatus(String machineId) {
        MachineInfo machine = controlServer.getMachineRegistry().get(machineId);
        if (machine != null) {
            return machine.getStatus().toString();
        }
        return "UNKNOWN";
    }
}