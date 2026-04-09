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

        String serverIp = config.getProperty("server.ip",       "localhost");
        int tcpPort     = Integer.parseInt(config.getProperty("server.tcp.port", "12345"));
        int udpPort     = Integer.parseInt(config.getProperty("server.udp.port", "12346"));

        // ── Ask player name ───────────────────────────────────────────────────
        String playerName = JOptionPane.showInputDialog(null,
                "Enter your player name:", "ChronoArena", JOptionPane.PLAIN_MESSAGE);
        if (playerName == null || playerName.trim().isEmpty()) System.exit(0);
        playerName = playerName.trim();

        // ── Demo / preview mode (no server) ──────────────────────────────────
        //    Remove this block once the server is ready.
        if (args.length > 0 && args[0].equals("--demo")) {
            launchDemo(playerName);
            return;
        }

        // ── Connect TCP ───────────────────────────────────────────────────────
        Socket tcpSocket;
        try {
            tcpSocket = new Socket(serverIp, tcpPort);
        } catch (ConnectException ex) {
            int choice = JOptionPane.showConfirmDialog(null,
                    "Could not connect to server at " + serverIp + ":" + tcpPort
                    + ".\n\nLaunch in demo / preview mode instead?",
                    "Connection Failed", JOptionPane.YES_NO_OPTION);
            if (choice == JOptionPane.YES_OPTION) {
                launchDemo(playerName);
            }
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
        InetAddress serverAddr   = InetAddress.getByName(serverIp);

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
            } catch (IOException ignored) {}
        }));
    }

    // ── Demo mode: fake game state so the GUI can be previewed without a server
    private static void launchDemo(String playerName) {
        String myId = "p1";

        // Build a fake game state
        GameState state = new GameState(150);
        state.currentTick = 42;

        // Players
        Player me = new Player(myId, playerName, 2, 2, new Color(80, 130, 255));
        me.score = 120;
        state.players.add(me);

        Player p2 = new Player("p2", "Ben", 10, 7, new Color(255, 80, 80));
        p2.score = 90;
        state.players.add(p2);

        Player p3 = new Player("p3", "Thomas", 17, 12, new Color(80, 200, 80));
        p3.score = 75;
        p3.frozen = true;
        p3.frozenUntil = System.currentTimeMillis() + 2000;
        state.players.add(p3);

        Player p4 = new Player("p4", "Jacob", 17, 2, new Color(255, 160, 0));
        p4.score = 55;
        state.players.add(p4);

        // Zones
        Zone zA = new Zone("A", 3, 2, 3, 3);
        zA.ownerId = "p2";
        zA.captureProgress = 1.0f;
        state.zones.add(zA);

        Zone zB = new Zone("B", 9, 6, 3, 3);
        zB.ownerId = myId;
        zB.contestedBy = "p2";
        zB.captureProgress = 0.7f;
        state.zones.add(zB);

        Zone zC = new Zone("C", 15, 10, 3, 3);
        zC.captureProgress = 0.3f;
        state.zones.add(zC);

        // Items
        state.items.add(new Item("i1", 6,  1,  Item.ItemType.ENERGY));
        state.items.add(new Item("i2", 13, 4,  Item.ItemType.FREEZE_RAY));
        state.items.add(new Item("i3", 1,  10, Item.ItemType.SPEED_BOOST));
        state.items.add(new Item("i4", 18, 8,  Item.ItemType.ENERGY));

        // Launch GUI with mock state
        GamePanel panel = new GamePanel(myId);
        panel.updateState(state);
        launchFrame("ChronoArena — DEMO MODE", panel);

        // Simulate ticking time so the HUD updates
        javax.swing.Timer ticker = new javax.swing.Timer(1000, e -> {
            state.timeRemaining = Math.max(0, state.timeRemaining - 1);
            state.currentTick++;
            panel.updateState(state);
        });
        ticker.start();
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
