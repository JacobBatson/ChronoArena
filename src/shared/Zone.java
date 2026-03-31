import java.io.Serializable;

public class Zone implements Serializable {
    public String zoneId;
    public int x, y, width, height;
    public String ownerId;
    public float captureProgress;
    public String contestedBy;
    public long graceTimerExpiry;
    public int pointsPerSecond;

    public Zone(String zoneId, int x, int y, int width, int height) {
        this.zoneId = zoneId;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.ownerId = null;
        this.captureProgress = 0f;
        this.contestedBy = null;
        this.graceTimerExpiry = -1;
        this.pointsPerSecond = 2;
    }
}