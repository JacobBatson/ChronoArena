package shared;

import java.io.Serializable;

public class Message implements Serializable {
    public enum Type {
        JOIN, JOIN_ACK, LEAVE, GAME_STATE, KILL, CHAT, WHISPER
    }

    public Type type;
    public Object data;

    public Message(Type type, Object data) {
        this.type = type;
        this.data = data;
    }
}
