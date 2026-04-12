package client;

import shared.*;
import shared.ChatMessage;

import javax.swing.*;
import java.io.*;
import java.net.*;

public class ServerListener implements Runnable {

    private final ObjectInputStream in;
    private final GamePanel panel;
    private final Socket socket;

    public ServerListener(ObjectInputStream in, GamePanel panel, Socket socket) {
        this.in = in;
        this.panel = panel;
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            while (true) {
                Message msg = (Message) in.readObject();
                switch (msg.type) {
                    case GAME_STATE:
                        panel.updateState((GameState) msg.data);
                        break;
                    case KILL:
                        JOptionPane.showMessageDialog(null,
                                "You were kicked by the server:\n" + msg.data,
                                "Disconnected", JOptionPane.WARNING_MESSAGE);
                        System.exit(0);
                        break;
                    case CHAT:
                        panel.receiveChat((ChatMessage) msg.data, false);
                        break;
                    case WHISPER:
                        panel.receiveChat((ChatMessage) msg.data, true);
                        break;
                    default:
                        break;
                }
            }
        } catch (EOFException | SocketException e) {
            // Server closed connection
            if (!socket.isClosed()) {
                JOptionPane.showMessageDialog(null,
                        "Lost connection to server.",
                        "Disconnected", JOptionPane.ERROR_MESSAGE);
            }
            System.exit(0);
        } catch (Exception e) {
            System.err.println("ServerListener error: " + e.getMessage());
            System.exit(0);
        }
    }
}
