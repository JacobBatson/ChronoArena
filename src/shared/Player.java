package shared;

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
    public long speedBoostUntil;
    public boolean hasWeapon;
    public boolean connected;
    public int lastMoveSeq;
    public int lastActionSeq;
    public int activeEmote = 0;
    public long emoteStartTime = 0;
    public long emoteEndTime = 0;

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
        this.lastMoveSeq = -1;
        this.lastActionSeq = -1;
    }
}