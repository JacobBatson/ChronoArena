package client;

import shared.*;

import javax.swing.*;
import java.awt.Color;
import java.util.Properties;
import java.io.*;
import java.net.*;

public class ClientMain {

    public static void main(String[] args) throws Exception {

        // ── Load config ───────────────────────────────────────────────────────
        Properties config = new Properties();
        File configFile = new File("config.properties");
        if (configFile.exists()) {
            config.load(new FileInputStream(configFile));
        } else {
            System.out.println("config.properties not found — using defaults");
        }

        int tcpPort = Integer.parseInt(config.getProperty("server.tcp.port", "12345"));
        int udpPort = Integer.parseInt(config.getProperty("server.udp.port", "12346"));

        // ── Ask server IP ─────────────────────────────────────────────────────
        String defaultIp = config.getProperty("server.ip", "localhost");
        String serverIp = JOptionPane.showInputDialog(null,
                "Enter server IP address:", defaultIp);
        if (serverIp == null)
            System.exit(0);
        if (serverIp.trim().isEmpty())
            serverIp = defaultIp;
        else
            serverIp = serverIp.trim();

        // ── Ask player name ───────────────────────────────────────────────────
        String playerName = JOptionPane.showInputDialog(null,
                "Enter your player name:", "ChronoArena", JOptionPane.PLAIN_MESSAGE);
        if (playerName == null || playerName.trim().isEmpty())
            System.exit(0);
        playerName = playerName.trim();

        // ── Connect TCP ───────────────────────────────────────────────────────
        Socket tcpSocket;
        try {
            tcpSocket = new Socket(serverIp, tcpPort);
        } catch (ConnectException ex) {
            JOptionPane.showMessageDialog(null,
                    "Could not connect to server at " + serverIp + ":" + tcpPort
                            + ".\n\nMake sure the server is running and try again.",
                    "Connection Failed", JOptionPane.ERROR_MESSAGE);
            return;
        }

        ObjectOutputStream out = new ObjectOutputStream(tcpSocket.getOutputStream());
        out.flush();
        ObjectInputStream in = new ObjectInputStream(tcpSocket.getInputStream());

        // ── JOIN handshake ────────────────────────────────────────────────────
        out.writeObject(new Message(Message.Type.JOIN, playerName));
        out.flush();

        Message ack = (Message) in.readObject();
        if (ack.type != Message.Type.JOIN_ACK) {
            JOptionPane.showMessageDialog(null, "Server rejected join.");
            return;
        }
        String playerId = (String) ack.data;
        System.out.println("Joined as: " + playerId);

        // ── UDP socket ────────────────────────────────────────────────────────
        DatagramSocket udpSocket = new DatagramSocket();
        InetAddress serverAddr = InetAddress.getByName(serverIp);

        // ── Build GUI ─────────────────────────────────────────────────────────
        GamePanel panel = new GamePanel(playerId, out, udpSocket, serverAddr, udpPort);
        launchFrame("ChronoArena — " + playerName, panel);

        // ── Server listener (TCP state updates) ───────────────────────────────
        new Thread(new ServerListener(in, panel, tcpSocket)).start();

        // ── Graceful disconnect on window close ───────────────────────────────
        // (handled via JFrame defaultCloseOperation + shutdown hook)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                out.writeObject(new Message(Message.Type.LEAVE, playerId));
                out.flush();
            } catch (IOException ignored) {
            }
        }));
    }

    private static void launchFrame(String title, GamePanel panel) {
        JFrame frame = new JFrame(title);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(panel);
        frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        frame.setVisible(true);
        panel.requestFocusInWindow();
    }
}
