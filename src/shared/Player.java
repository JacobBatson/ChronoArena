import java.awt.Color;
import java.io.Serializable;

public class Player implements Serializable {
    public String playerId;
    public String name;
    public int gridX, gridY;
    public int score;
    public Color color;
    public boolean frozen;
    public long frozenUntil;
    public boolean hasWeapon;
    public boolean connected;
    public int lastSeqNumber;

    public Player(String playerId, String name, 
                  int gridX, int gridY, Color color) {
        this.playerId = playerId;
        this.name = name;
        this.gridX = gridX;
        this.gridY = gridY;
        this.color = color;
        this.score = 0;
        this.frozen = false;
        this.hasWeapon = false;
        this.connected = true;
        this.lastSeqNumber = -1;
    }
}