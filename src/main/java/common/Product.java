package common;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Représente un produit final assemblé
 */
public class Product implements Serializable {
    private static final long serialVersionUID = 1L;

    private String productId;
    private List<Component> components;
    private Date assemblyDate;
    private int requiredComponentsCount;

    public Product(String productId, int requiredComponentsCount) {
        this.productId = productId;
        this.components = new ArrayList<>();
        this.requiredComponentsCount = requiredComponentsCount;
        this.assemblyDate = new Date();
    }

    public void addComponent(Component component) {
        if (components.size() < requiredComponentsCount) {
            components.add(component);
        }
    }

    public boolean isComplete() {
        return components.size() >= requiredComponentsCount;
    }

    public String getProductId() {
        return productId;
    }

    public List<Component> getComponents() {
        return components;
    }

    public Date getAssemblyDate() {
        return assemblyDate;
    }

    public int getRequiredComponentsCount() {
        return requiredComponentsCount;
    }

    @Override
    public String toString() {
        return "Product{" +
                "id='" + productId + '\'' +
                ", components=" + components.size() + "/" + requiredComponentsCount +
                ", complete=" + isComplete() +
                ", date=" + assemblyDate +
                '}';
    }
}