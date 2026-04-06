package server;

import shared.*;

import java.io.*;
import java.net.*;

/**
 * ClientHandler — manages one connected client over TCP.
 * Implements GameEngine.Listener so it receives every tick and broadcasts
 * the updated GameState to its client.
 */
public class ClientHandler implements GameEngine.Listener, Runnable {

    private final String          playerId;
    private final Socket          socket;
    private final ObjectOutputStream out;
    private final ObjectInputStream  in;
    private final GameEngine      engine;

    public ClientHandler(String playerId, Socket socket,
                         ObjectOutputStream out, ObjectInputStream in,
                         GameEngine engine) {
        this.playerId = playerId;
        this.socket   = socket;
        this.out      = out;
        this.in       = in;
        this.engine   = engine;
    }

    // ── GameEngine.Listener ───────────────────────────────────────────────────

    @Override
    public synchronized void onTick(GameState state) {
        send(new Message(Message.Type.GAME_STATE, state));
    }

    @Override
    public synchronized void onPlayerKilled(String killedId, String reason) {
        if (!killedId.equals(playerId)) return;
        send(new Message(Message.Type.KILL, reason));
        close();
    }

    // ── Read loop (detects LEAVE and unexpected disconnects) ──────────────────

    @Override
    public void run() {
        try {
            while (!socket.isClosed()) {
                Message msg = (Message) in.readObject();
                if (msg.type == Message.Type.LEAVE) {
                    System.out.println("[Server] Player " + playerId + " left.");
                    break;
                }
            }
        } catch (EOFException | SocketException ignored) {
            // Client disconnected
        } catch (Exception e) {
            System.err.println("[ClientHandler] Error for " + playerId + ": " + e.getMessage());
        } finally {
            engine.removePlayer(playerId);
            close();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private synchronized void send(Message msg) {
        try {
            out.writeObject(msg);
            out.flush();
            out.reset(); // prevent object cache growing unbounded
        } catch (IOException e) {
            // Client gone — will be cleaned up by run() loop
        }
    }

    private void close() {
        try { socket.close(); } catch (IOException ignored) {}
    }
}
