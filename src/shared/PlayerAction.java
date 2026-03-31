import java.io.Serializable;

public class PlayerAction implements Serializable {
    public String playerId;
    public ActionType type;
    public int dx, dy;
    public int seqNumber;
    public long timestamp;

    public PlayerAction(String playerId, ActionType type, 
                        int dx, int dy, int seqNumber) {
        this.playerId = playerId;
        this.type = type;
        this.dx = dx;
        this.dy = dy;
        this.seqNumber = seqNumber;
        this.timestamp = System.currentTimeMillis();
    }
}