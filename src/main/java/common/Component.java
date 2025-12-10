package common;

import java.io.Serializable;
import java.util.Date;

/**
 * Représente un composant fabriqué par une machine de production
 */
public class Component implements Serializable {
    private static final long serialVersionUID = 1L;

    private String componentId;
    private String type;
    private String producedBy;
    private boolean isDefective;
    private Date productionDate;

    public Component(String componentId, String type, String producedBy) {
        this.componentId = componentId;
        this.type = type;
        this.producedBy = producedBy;
        this.isDefective = false;
        this.productionDate = new Date();
    }

    public String getComponentId() {
        return componentId;
    }

    public void setComponentId(String componentId) {
        this.componentId = componentId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getProducedBy() {
        return producedBy;
    }

    public void setProducedBy(String producedBy) {
        this.producedBy = producedBy;
    }

    public boolean isDefective() {
        return isDefective;
    }

    public void setDefective(boolean defective) {
        isDefective = defective;
    }

    public Date getProductionDate() {
        return productionDate;
    }

    @Override
    public String toString() {
        return "Component{" +
                "id='" + componentId + '\'' +
                ", type='" + type + '\'' +
                ", producedBy='" + producedBy + '\'' +
                ", defective=" + isDefective +
                ", date=" + productionDate +
                '}';
    }
}