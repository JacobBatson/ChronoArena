import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class GameState implements Serializable {
    public List<Player> players = new ArrayList<>();
    public List<Zone> zones = new ArrayList<>();
    public List<Item> items = new ArrayList<>();
    public int timeRemaining;
    public int currentTick;
    public boolean gameOver;
    public String winnerId;

    public GameState(int gameDuration) {
        this.timeRemaining = gameDuration;
        this.currentTick = 0;
        this.gameOver = false;
        this.winnerId = null;
    }
}
