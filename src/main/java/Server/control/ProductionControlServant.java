package Server.control;

import common.Component;
import ProductionControlModule.*;

/**
 * Servant CORBA - Délègue tout au ControlServer
 */
public class ProductionControlServant extends IProductionControlPOA {

    private ControlServer server;

    public ProductionControlServant(ControlServer server) {
        this.server = server;
    }

    @Override
    public boolean registerMachine(String machineId, String machineType) {
        return server.registerMachine(machineId, machineType);
    }

    @Override
    public String notifyFailure(String machineId, String errorType) {
        return server.handleFailure(machineId, errorType);
    }

    @Override
    public boolean requestProductionStart(String machineId) {
        return server.startMachine(machineId);
    }

    @Override
    public boolean requestProductionStop(String machineId) {
        return server.stopMachine(machineId);
    }

    @Override
    public boolean deliverComponent(ComponentData compData) {
        Component comp = new Component(
                compData.componentId,
                compData.type,
                compData.producedBy
        );
        comp.setDefective(compData.isDefective);
        return server.deliverComponent(comp);
    }

    @Override
    public String getMachineStatus(String machineId) {
        return server.getMachineStatus(machineId);
    }

    @Override
    public boolean registerAssemblyStation(String stationId, IStationCallback callback) {
        return server.registerStation(stationId, callback);
    }

    @Override
    public void notifyStorageAlert(String zoneId, int level) {
        server.handleStorageAlert(zoneId, level);
    }

    @Override
    public String getSystemStatus() {
        return server.getSystemStatus();
    }
}