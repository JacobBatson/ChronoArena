package server;

import client.GamePanel;
import shared.*;

import javax.swing.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class ServerMain {

    public static void main(String[] args) throws Exception {

        // ── Load config ───────────────────────────────────────────────────────
        Properties config = new Properties();
        File configFile = new File("config.properties");
        if (configFile.exists()) {
            config.load(new FileInputStream(configFile));
        } else {
            System.out.println("config.properties not found — using defaults");
        }

        int tcpPort      = Integer.parseInt(config.getProperty("server.tcp.port",  "12345"));
        int udpPort      = Integer.parseInt(config.getProperty("server.udp.port",  "12346"));
        int gameDuration = Integer.parseInt(config.getProperty("game.duration",    "120"));

        // ── Create engine (duration overridden at Start) ──────────────────────
        GameEngine engine = new GameEngine(gameDuration);

        // ── UDP receive loop ──────────────────────────────────────────────────
        DatagramSocket udpSocket = new DatagramSocket(udpPort);
        Thread udpThread = new Thread(() -> {
            byte[] buf = new byte[4096];
            while (true) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(packet);
                    ByteArrayInputStream bais = new ByteArrayInputStream(packet.getData(), 0, packet.getLength());
                    ObjectInputStream ois = new ObjectInputStream(bais);
                    PlayerAction action = (PlayerAction) ois.readObject();
                    engine.enqueueAction(action);
                } catch (Exception e) {
                    System.err.println("[UDP] Error: " + e.getMessage());
                }
            }
        }, "UDP-Receiver");
        udpThread.setDaemon(true);
        udpThread.start();

        // ── TCP accept loop ───────────────────────────────────────────────────
        ServerSocket serverSocket = new ServerSocket(tcpPort);
        Thread tcpThread = new Thread(() -> {
            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    ObjectOutputStream out = new ObjectOutputStream(clientSocket.getOutputStream());
                    out.flush();
                    ObjectInputStream in = new ObjectInputStream(clientSocket.getInputStream());

                    Message joinMsg = (Message) in.readObject();
                    if (joinMsg.type != Message.Type.JOIN) {
                        clientSocket.close();
                        continue;
                    }

                    String playerName = (String) joinMsg.data;
                    Player player = engine.addPlayer(playerName);

                    out.writeObject(new Message(Message.Type.JOIN_ACK, player.playerId));
                    out.flush();

                    ClientHandler handler = new ClientHandler(player.playerId, clientSocket, out, in, engine);
                    engine.addListener(handler);
                    new Thread(handler, "Client-" + player.playerId).start();

                    System.out.println("[Server] " + playerName + " connected as " + player.playerId);
                } catch (Exception e) {
                    System.err.println("[TCP Accept] Error: " + e.getMessage());
                }
            }
        }, "TCP-Acceptor");
        tcpThread.setDaemon(true);
        tcpThread.start();

        System.out.println("[Server] Listening on TCP:" + tcpPort + " UDP:" + udpPort);

        // ── Start game loop (paused until operator clicks Start) ──────────────
        engine.start();

        // ── Build the window ──────────────────────────────────────────────────
        JFrame[]     frameRef  = new JFrame[1];
        LobbyPanel[] lobbyRef  = new LobbyPanel[1];

        lobbyRef[0] = new LobbyPanel(() -> {
            // Called on EDT when operator clicks "Start Game"
            int seconds = lobbyRef[0].getDurationSeconds();
            engine.setDuration(seconds);
            engine.startGame();

            // Swap lobby panel for the live game panel
            GamePanel serverPanel = new GamePanel("SERVER", null, null, null, 0);
            engine.addListener(new GameEngine.Listener() {
                @Override
                public void onTick(GameState state) { serverPanel.updateState(state); }
                @Override
                public void onPlayerKilled(String playerId, String reason) {}
            });

            JFrame frame = frameRef[0];
            frame.getContentPane().removeAll();
            frame.getContentPane().add(serverPanel);
            frame.revalidate();
            frame.repaint();
        });

        engine.addListener(lobbyRef[0]);

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("ChronoArena — SERVER");
            frameRef[0] = frame;
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.add(lobbyRef[0]);
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            frame.setVisible(true);
        });

        // ── Admin stdin: "kill <playerId> [reason]" ───────────────────────────
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();
            if (line.startsWith("kill ")) {
                String[] parts = line.split(" ", 3);
                String id     = parts[1];
                String reason = parts.length > 2 ? parts[2] : "Kicked by server";
                engine.killPlayer(id, reason);
            }
        }
    }
}
