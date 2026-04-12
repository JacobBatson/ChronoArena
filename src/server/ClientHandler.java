package server;

import shared.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.ConcurrentHashMap;

public class ClientHandler implements GameEngine.Listener, Runnable {

    // ── Static registry: playerId → handler (for whisper routing) ────────────
    public static final ConcurrentHashMap<String, ClientHandler> ALL = new ConcurrentHashMap<>();

    private final String playerId;
    private final Socket socket;
    private final ObjectOutputStream out;
    private final ObjectInputStream in;
    private final GameEngine engine;

    public ClientHandler(String playerId, Socket socket,
            ObjectOutputStream out, ObjectInputStream in,
            GameEngine engine) {
        this.playerId = playerId;
        this.socket = socket;
        this.out = out;
        this.in = in;
        this.engine = engine;
        ALL.put(playerId, this); // register
    }

    // ── GameEngine.Listener ───────────────────────────────────────────────────
    @Override
    public synchronized void onTick(GameState state) {
        send(new Message(Message.Type.GAME_STATE, state));
    }

    @Override
    public synchronized void onPlayerKilled(String killedId, String reason) {
        if (!killedId.equals(playerId))
            return;
        send(new Message(Message.Type.KILL, reason));
        close();
    }

    // ── Read loop ─────────────────────────────────────────────────────────────
    @Override
    public void run() {
        try {
            while (!socket.isClosed()) {
                Message msg = (Message) in.readObject();

                if (msg.type == Message.Type.LEAVE) {
                    System.out.println("[Server] Player " + playerId + " left.");
                    break;

                } else if (msg.type == Message.Type.CHAT) {
                    // Global broadcast
                    ChatMessage cm = (ChatMessage) msg.data;
                    System.out.println("[Chat] " + cm.senderName + ": " + cm.text);
                    Message outMsg = new Message(Message.Type.CHAT, cm);
                    for (ClientHandler h : ALL.values())
                        h.send(outMsg);

                } else if (msg.type == Message.Type.WHISPER) {
                    // Private message — route only to sender + target
                    ChatMessage cm = (ChatMessage) msg.data;
                    System.out.println("[Whisper] " + cm.senderName + " → " + cm.targetId + ": " + cm.text);
                    Message outMsg = new Message(Message.Type.WHISPER, cm);
                    send(outMsg); // echo to sender
                    ClientHandler target = ALL.get(cm.targetId);
                    if (target != null) {
                        target.send(outMsg); // deliver to target
                    }
                }
            }
        } catch (EOFException | SocketException ignored) {
        } catch (Exception e) {
            System.err.println("[ClientHandler] Error for " + playerId + ": " + e.getMessage());
        } finally {
            ALL.remove(playerId); // unregister
            engine.removePlayer(playerId);
            close();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private synchronized void send(Message msg) {
        try {
            out.writeObject(msg);
            out.flush();
            out.reset();
        } catch (IOException ignored) {
        }
    }

    private void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}