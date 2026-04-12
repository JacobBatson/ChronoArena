package shared;

import java.io.Serializable;

public class ChatMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    public final String senderId; // Same as playerId
    public final String senderName;
    public final String targetId; // null = global | playerId = whisper
    public final String text;
    public final long timestamp;

    public ChatMessage(String senderId, String senderName, String targetId, String text) {
        this.senderId = senderId;
        this.senderName = senderName;
        this.targetId = targetId;
        this.text = text;
        this.timestamp = System.currentTimeMillis();
    }

    public boolean isWhisper() {
        return targetId != null;
    }
}