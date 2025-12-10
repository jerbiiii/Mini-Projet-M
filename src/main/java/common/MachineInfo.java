package common;

import java.io.Serializable;

/**
 * Classe contenant les informations d'une machine de production
 */
public class MachineInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum MachineStatus {
        RUNNING, STOPPED, FAILED, MAINTENANCE
    }

    private String machineId;
    private String machineType;
    private MachineStatus status;
    private String lastError;
    private int productionCount;

    public MachineInfo(String machineId, String machineType) {
        this.machineId = machineId;
        this.machineType = machineType;
        this.status = MachineStatus.STOPPED;
        this.productionCount = 0;
    }

    public String getMachineId() {
        return machineId;
    }

    public String getMachineType() {
        return machineType;
    }

    public MachineStatus getStatus() {
        return status;
    }

    public void setStatus(MachineStatus status) {
        this.status = status;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public int getProductionCount() {
        return productionCount;
    }

    public void incrementProductionCount() {
        this.productionCount++;
    }

    @Override
    public String toString() {
        return "MachineInfo{" +
                "id='" + machineId + '\'' +
                ", type='" + machineType + '\'' +
                ", status=" + status +
                ", error='" + lastError + '\'' +
                ", produced=" + productionCount +
                '}';
    }
}