package shared;

import java.io.Serializable;

public class Item implements Serializable {
    public String itemId;
    public int gridX, gridY;
    public ItemType type;
    public boolean collected;

    public enum ItemType {
        ENERGY,
        FREEZE_RAY,
        SPEED_BOOST
    }

    public Item(String itemId, int gridX, int gridY, ItemType type) {
        this.itemId = itemId;
        this.gridX = gridX;
        this.gridY = gridY;
        this.type = type;
        this.collected = false;
    }
}