package client;

import shared.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.List;
import javax.swing.Timer;
import java.awt.Toolkit;

public class GamePanel extends JPanel implements KeyListener {

    // ── Grid / layout constants ──────────────────────────────────────────────
    public static final int CELL     = 40;
    public static final int COLS     = 20;
    public static final int ROWS     = 15;
    public static final int HUD_H    = 90;
    public static final int STATUS_H = 50;

    private static final int W = COLS * CELL;
    private static final int H = HUD_H + ROWS * CELL + STATUS_H;

    // ── Colors ────────────────────────────────────────────────────────────────
    private static final Color BG          = new Color(30, 30, 40);
    private static final Color GRID_LINE   = new Color(50, 50, 65);
    private static final Color HUD_BG      = new Color(15, 15, 25);
    private static final Color STATUS_BG   = new Color(15, 15, 25);
    private static final Color ZONE_UNCL   = new Color(70, 70, 90, 80);
    private static final Color ZONE_BORDER = new Color(110, 110, 140);

    // ── Cooldown ──────────────────────────────────────────────────────────────
    private static final long COOLDOWN_MS = 5000; // 5 seconds after firing
    private long lastFiredTime = 0;

    // ── Floating text ─────────────────────────────────────────────────────────
    private static class FloatingText {
        String text;
        float x, y;       // pixel position in arena
        Color color;
        float alpha = 1f;
        float life  = 1.5f; // seconds to live

        FloatingText(String text, int gridX, int gridY, Color color) {
            this.text  = text;
            this.x     = gridX * CELL + CELL / 2f;
            this.y     = HUD_H + gridY * CELL + CELL / 2f;
            this.color = color;
        }
    }
    private final List<FloatingText> floatingTexts = new ArrayList<>();

    // ── State ─────────────────────────────────────────────────────────────────
    private volatile GameState gameState;
    private GameState prevState;
    private final String myPlayerId;

    // ── Networking ────────────────────────────────────────────────────────────
    private ObjectOutputStream tcpOut;
    private DatagramSocket udpSocket;
    private InetAddress serverAddr;
    private int udpPort;
    private int seqNumber = 0;

    // ── Input ─────────────────────────────────────────────────────────────────
    private final boolean[] keys = new boolean[512];
    private boolean gameOverShown = false;

    // ─────────────────────────────────────────────────────────────────────────
    // Constructors
    // ─────────────────────────────────────────────────────────────────────────

    public GamePanel(String myPlayerId, ObjectOutputStream tcpOut,
                     DatagramSocket udpSocket, InetAddress serverAddr, int udpPort) {
        this.myPlayerId  = myPlayerId;
        this.tcpOut      = tcpOut;
        this.udpSocket   = udpSocket;
        this.serverAddr  = serverAddr;
        this.udpPort     = udpPort;
        init();
    }

    private void init() {
        setPreferredSize(Toolkit.getDefaultToolkit().getScreenSize());
        setBackground(BG);
        setFocusable(true);
        addKeyListener(this);

        // Movement input loop
        new Timer(100, e -> {
            if (gameState != null && !gameState.gameOver) processHeldKeys();
        }).start();

        // Floating text animation loop (~30fps)
        new Timer(33, e -> {
            boolean any = false;
            synchronized (floatingTexts) {
                Iterator<FloatingText> it = floatingTexts.iterator();
                while (it.hasNext()) {
                    FloatingText ft = it.next();
                    ft.y    -= 0.8f;           // float upward
                    ft.life -= 0.033f;
                    ft.alpha = Math.max(0, ft.life / 1.5f);
                    if (ft.life <= 0) it.remove();
                    any = true;
                }
            }
            if (any) repaint();
        }).start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    public void updateState(GameState state) {
        detectEvents(state);   // check for freeze events before swapping state
        this.gameState = state;
        SwingUtilities.invokeLater(this::repaint);
        if (state.gameOver && !gameOverShown) {
            gameOverShown = true;
            SwingUtilities.invokeLater(() -> showGameOver(state));
        }
    }

    /** Compare new state vs old to spawn floating text on freeze events. */
    private void detectEvents(GameState newState) {
        if (prevState == null) {
            prevState = newState;
            return;
        }
        for (Player np : newState.players) {
            Player op = findPlayerInState(prevState, np.playerId);
            if (op != null && !op.frozen && np.frozen) {
                // This player just got frozen
                spawnFloatingText("TAGGED!  -10 pts", np.gridX, np.gridY, new Color(255, 70, 70));
            }
        }
        prevState = newState;
    }

    private void spawnFloatingText(String text, int gridX, int gridY, Color color) {
        synchronized (floatingTexts) {
            floatingTexts.add(new FloatingText(text, gridX, gridY, color));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Input
    // ─────────────────────────────────────────────────────────────────────────

    private void processHeldKeys() {
        if      (keys[KeyEvent.VK_LEFT]  || keys[KeyEvent.VK_A]) sendMove(-1,  0);
        else if (keys[KeyEvent.VK_RIGHT] || keys[KeyEvent.VK_D]) sendMove( 1,  0);
        else if (keys[KeyEvent.VK_UP]    || keys[KeyEvent.VK_W]) sendMove( 0, -1);
        else if (keys[KeyEvent.VK_DOWN]  || keys[KeyEvent.VK_S]) sendMove( 0,  1);
    }

    private void sendMove(int dx, int dy) {
        sendUDP(new PlayerAction(myPlayerId, ActionType.MOVE, dx, dy, seqNumber++));
    }

    private void sendUDP(PlayerAction action) {
        if (udpSocket == null || serverAddr == null) return;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(action);
            oos.flush();
            byte[] data = baos.toByteArray();
            udpSocket.send(new DatagramPacket(data, data.length, serverAddr, udpPort));
        } catch (IOException e) {
            System.err.println("UDP send error: " + e.getMessage());
        }
    }

    @Override
    public void keyPressed(KeyEvent e) {
        int k = e.getKeyCode();
        if (k < keys.length) keys[k] = true;

        if (k == KeyEvent.VK_SPACE) {
            long now = System.currentTimeMillis();
            if (now - lastFiredTime >= COOLDOWN_MS) {
                lastFiredTime = now;
                sendUDP(new PlayerAction(myPlayerId, ActionType.USE_ITEM, 0, 0, seqNumber++));
            }
        }
    }

    @Override public void keyReleased(KeyEvent e) { if (e.getKeyCode() < keys.length) keys[e.getKeyCode()] = false; }
    @Override public void keyTyped(KeyEvent e) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Painting
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,        RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,   RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,       RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // Fill entire window background
        g2.setColor(BG);
        g2.fillRect(0, 0, getWidth(), getHeight());

        // Scale content to fill window, maintaining aspect ratio
        float scaleX = (float) getWidth()  / W;
        float scaleY = (float) getHeight() / H;
        float scale  = Math.min(scaleX, scaleY);
        float offsetX = (getWidth()  - W * scale) / 2f;
        float offsetY = (getHeight() - H * scale) / 2f;

        g2.translate(offsetX, offsetY);
        g2.scale(scale, scale);

        if (gameState == null) { drawWaiting(g2); return; }

        drawHUD(g2);
        drawArena(g2);
        drawFloatingTexts(g2);
        drawStatusBar(g2);
        if (gameState.gameOver) drawGameOverOverlay(g2);
    }

    // ── Waiting screen ────────────────────────────────────────────────────────
    private void drawWaiting(Graphics2D g) {
        g.setColor(BG);
        g.fillRect(0, 0, W, H);
        g.setColor(new Color(180, 180, 220));
        g.setFont(new Font("Monospaced", Font.BOLD, 28));
        String msg = "Connecting to server...";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(msg, (W - fm.stringWidth(msg)) / 2, H / 2);
    }

    // ── HUD ───────────────────────────────────────────────────────────────────
    private void drawHUD(Graphics2D g) {
        g.setColor(HUD_BG);
        g.fillRect(0, 0, W, HUD_H);
        g.setColor(new Color(60, 60, 90));
        g.fillRect(0, HUD_H - 2, W, 2);

        // Title
        g.setFont(new Font("Monospaced", Font.BOLD, 22));
        g.setColor(new Color(200, 180, 255));
        g.drawString("CHRONOARENA", 14, 32);

        // Timer
        int mins = gameState.timeRemaining / 60;
        int secs = gameState.timeRemaining % 60;
        // Score pills (row 1, right side)
        List<Player> sorted = new ArrayList<>(gameState.players);
        sorted.sort((a, b) -> b.score - a.score);
        int sx = W - 14;
        g.setFont(new Font("Monospaced", Font.BOLD, 15));
        FontMetrics fm;
        for (int i = sorted.size() - 1; i >= 0; i--) {
            Player p = sorted.get(i);
            String label = p.name + ": " + p.score;
            fm = g.getFontMetrics();
            sx -= fm.stringWidth(label) + 6;
            g.setColor(p.color.darker().darker());
            g.fillRoundRect(sx - 4, 8, fm.stringWidth(label) + 8, 22, 8, 8);
            g.setColor(p.color);
            g.drawString(label, sx, 24);
            sx -= 8;
        }

        // Timer — right-aligned, directly under the score pills
        String timeStr = String.format("TIME LEFT  %02d:%02d", mins, secs);
        g.setFont(new Font("Monospaced", Font.BOLD, 16));
        g.setColor(gameState.timeRemaining <= 30 ? new Color(255, 80, 80) : Color.WHITE);
        fm = g.getFontMetrics();
        g.drawString(timeStr, W - fm.stringWidth(timeStr) - 14, 46);

        // Legend
        g.setFont(new Font("Monospaced", Font.PLAIN, 11));
        g.setColor(new Color(130, 130, 160));
        g.drawString("WASD / Arrows: Move   |   SPACE: Fire Freeze Ray", 14, 56);
        g.setColor(new Color(100, 100, 130));
        g.drawString("Tick #" + gameState.currentTick, 14, 72);
    }

    // ── Arena ─────────────────────────────────────────────────────────────────
    private void drawArena(Graphics2D g) {
        int oy = HUD_H;
        g.setColor(BG);
        g.fillRect(0, oy, W, ROWS * CELL);

        // Grid lines
        g.setColor(GRID_LINE);
        g.setStroke(new BasicStroke(0.5f));
        for (int c = 0; c <= COLS; c++) g.drawLine(c * CELL, oy, c * CELL, oy + ROWS * CELL);
        for (int r = 0; r <= ROWS; r++) g.drawLine(0, oy + r * CELL, W, oy + r * CELL);
        g.setStroke(new BasicStroke(1f));

        for (Zone z : gameState.zones)  drawZone(g, z, oy);
        for (Item item : gameState.items) { if (!item.collected) drawItem(g, item, oy); }
        for (Player p : gameState.players) drawPlayer(g, p, oy);

        // Player info panel (top-left of arena)
        drawPlayerInfoPanel(g, oy);
    }

    // ── Player info panel (top-left corner of arena) ──────────────────────────
    private void drawPlayerInfoPanel(Graphics2D g, int oy) {
        Player me = findPlayer(myPlayerId);
        if (me == null) return;

        int px = 8, py = oy + 8;
        int pw = 155, ph = 80;

        // Panel background
        g.setColor(new Color(10, 10, 20, 200));
        g.fillRoundRect(px, py, pw, ph, 10, 10);
        g.setColor(me.color.darker());
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(px, py, pw, ph, 10, 10);
        g.setStroke(new BasicStroke(1f));

        // Avatar circle
        g.setColor(me.color);
        g.fillOval(px + 8, py + 10, 28, 28);
        if (me.frozen) {
            g.setColor(new Color(100, 200, 255, 180));
            g.fillOval(px + 8, py + 10, 28, 28);
        }

        // Name
        g.setFont(new Font("Monospaced", Font.BOLD, 13));
        g.setColor(Color.WHITE);
        String name = me.name.length() > 9 ? me.name.substring(0, 9) : me.name;
        g.drawString(name, px + 44, py + 22);

        // Score
        g.setFont(new Font("Monospaced", Font.PLAIN, 11));
        g.setColor(new Color(200, 200, 200));
        g.drawString("Score: " + me.score, px + 44, py + 36);

        // Status line
        g.setFont(new Font("Monospaced", Font.BOLD, 10));
        if (me.frozen) {
            g.setColor(new Color(100, 200, 255));
            g.drawString("** FROZEN **", px + 8, py + 58);
        } else if (me.hasWeapon) {
            g.setColor(new Color(0, 230, 255));
            g.drawString("FREEZE RAY READY", px + 8, py + 58);
        } else {
            g.setColor(new Color(160, 160, 180));
            g.drawString("No weapon", px + 8, py + 58);
        }

        // Speed boost
        if (me.speedBoostUntil > System.currentTimeMillis()) {
            g.setColor(new Color(220, 80, 255));
            g.drawString("SPEED BOOST!", px + 8, py + 70);
        }
    }

    // ── Zone ─────────────────────────────────────────────────────────────────
    private void drawZone(Graphics2D g, Zone zone, int oy) {
        int px = zone.x * CELL,  py = oy + zone.y * CELL;
        int pw = zone.width * CELL, ph = zone.height * CELL;

        Color fill;
        if (zone.ownerId != null) {
            Player owner = findPlayer(zone.ownerId);
            Color base = owner != null ? owner.color : Color.GRAY;
            fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), 55);
        } else {
            fill = ZONE_UNCL;
        }
        g.setColor(fill);
        g.fillRect(px, py, pw, ph);

        if (zone.contestedBy != null) {
            g.setColor(new Color(255, 180, 0));
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10, new float[]{6, 4}, 0));
        } else if (zone.ownerId != null) {
            Player owner = findPlayer(zone.ownerId);
            g.setColor(owner != null ? owner.color : Color.WHITE);
            g.setStroke(new BasicStroke(2.5f));
        } else {
            g.setColor(ZONE_BORDER);
            g.setStroke(new BasicStroke(1.5f));
        }
        g.drawRect(px, py, pw, ph);
        g.setStroke(new BasicStroke(1f));

        g.setFont(new Font("Monospaced", Font.BOLD, 12));
        g.setColor(new Color(220, 220, 255));
        g.drawString("ZONE " + zone.zoneId, px + 5, py + 16);

        g.setFont(new Font("Monospaced", Font.BOLD, 11));
        String statusText; Color statusColor;
        if (zone.contestedBy != null) {
            statusText = "CONTESTED"; statusColor = new Color(255, 180, 0);
        } else if (zone.ownerId != null) {
            Player owner = findPlayer(zone.ownerId);
            statusText  = owner != null ? owner.name.toUpperCase() : "OWNED";
            statusColor = owner != null ? owner.color : Color.GREEN;
        } else {
            statusText = "UNCLAIMED"; statusColor = new Color(160, 160, 190);
        }
        g.setColor(statusColor);
        g.drawString(statusText, px + 5, py + 30);

        if (zone.captureProgress > 0f && zone.captureProgress < 1f) {
            int barY = py + ph - 9, barW = pw - 6;
            g.setColor(new Color(30, 30, 50));
            g.fillRoundRect(px + 3, barY, barW, 6, 4, 4);
            g.setColor(new Color(100, 220, 100));
            g.fillRoundRect(px + 3, barY, (int)(barW * zone.captureProgress), 6, 4, 4);
        }

        if (zone.graceTimerExpiry > 0) {
            long rem = zone.graceTimerExpiry - System.currentTimeMillis();
            if (rem > 0) {
                g.setColor(new Color(255, 60, 60));
                g.fillOval(px + pw - 14, py + 5, 9, 9);
                g.setFont(new Font("Monospaced", Font.PLAIN, 9));
                g.setColor(Color.WHITE);
                g.drawString(String.valueOf((rem / 1000) + 1), px + pw - 13, py + 13);
            }
        }
    }

    // ── Item ─────────────────────────────────────────────────────────────────
    private void drawItem(Graphics2D g, Item item, int oy) {
        int cx = item.gridX * CELL + CELL / 2, cy = oy + item.gridY * CELL + CELL / 2, r = 10;
        switch (item.type) {
            case ENERGY:
                g.setColor(new Color(255, 210, 0));
                g.fillOval(cx - r, cy - r, r * 2, r * 2);
                g.setColor(new Color(200, 150, 0));
                g.setStroke(new BasicStroke(1.5f));
                g.drawOval(cx - r, cy - r, r * 2, r * 2);
                g.setStroke(new BasicStroke(1f));
                g.setFont(new Font("Monospaced", Font.BOLD, 11));
                g.setColor(new Color(180, 120, 0));
                g.drawString("E", cx - 4, cy + 4);
                break;
            case FREEZE_RAY:
                int[] xp = {cx, cx+r, cx, cx-r}, yp = {cy-r, cy, cy+r, cy};
                g.setColor(new Color(80, 220, 255, 200));
                g.fillPolygon(xp, yp, 4);
                g.setColor(new Color(0, 180, 240));
                g.setStroke(new BasicStroke(1.5f));
                g.drawPolygon(xp, yp, 4);
                g.setStroke(new BasicStroke(1f));
                g.setFont(new Font("Monospaced", Font.BOLD, 10));
                g.setColor(Color.WHITE);
                g.drawString("F", cx - 4, cy + 4);
                break;
            case SPEED_BOOST:
                g.setColor(new Color(220, 80, 255, 200));
                g.fillRoundRect(cx - r, cy - r, r * 2, r * 2, 6, 6);
                g.setColor(new Color(180, 40, 220));
                g.setStroke(new BasicStroke(1.5f));
                g.drawRoundRect(cx - r, cy - r, r * 2, r * 2, 6, 6);
                g.setStroke(new BasicStroke(1f));
                g.setFont(new Font("Monospaced", Font.BOLD, 10));
                g.setColor(Color.WHITE);
                g.drawString("S", cx - 4, cy + 4);
                break;
        }
    }

    // ── Player ────────────────────────────────────────────────────────────────
    private void drawPlayer(Graphics2D g, Player player, int oy) {
        int px = player.gridX * CELL, py = oy + player.gridY * CELL;
        int pad = 6, size = CELL - pad * 2;
        boolean isMe = player.playerId.equals(myPlayerId);

        if (player.frozen) {
            g.setColor(new Color(100, 200, 255, 60));
            g.fillRect(px, py, CELL, CELL);
        }
        if (player.speedBoostUntil > System.currentTimeMillis()) {
            g.setColor(new Color(220, 80, 255, 50));
            g.fillRect(px, py, CELL, CELL);
        }

        g.setColor(player.color);
        g.fillOval(px + pad, py + pad, size, size);

        if (isMe) {
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2.5f));
            g.drawOval(px + pad - 3, py + pad - 3, size + 6, size + 6);
            g.setStroke(new BasicStroke(1f));
        }
        if (player.hasWeapon) {
            g.setColor(new Color(0, 230, 255));
            g.fillOval(px + CELL - pad - 6, py + pad - 2, 9, 9);
        }
        if (player.frozen) {
            g.setFont(new Font("Monospaced", Font.BOLD, 9));
            g.setColor(Color.WHITE);
            g.drawString("ICE", px + pad + 3, py + pad + size / 2 + 3);
        }

        String name = player.name.length() > 7 ? player.name.substring(0, 7) : player.name;
        g.setFont(new Font("Monospaced", isMe ? Font.BOLD : Font.PLAIN, 9));
        g.setColor(isMe ? Color.WHITE : new Color(200, 200, 200));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(name, px + (CELL - fm.stringWidth(name)) / 2, py + CELL - 1);
    }

    // ── Floating text ─────────────────────────────────────────────────────────
    private void drawFloatingTexts(Graphics2D g) {
        synchronized (floatingTexts) {
            for (FloatingText ft : floatingTexts) {
                g.setFont(new Font("Monospaced", Font.BOLD, 16));
                FontMetrics fm = g.getFontMetrics();
                int tx = (int)(ft.x - fm.stringWidth(ft.text) / 2f);
                int ty = (int) ft.y;

                // Shadow
                g.setColor(new Color(0, 0, 0, (int)(ft.alpha * 180)));
                g.drawString(ft.text, tx + 1, ty + 1);

                // Text
                g.setColor(new Color(ft.color.getRed(), ft.color.getGreen(),
                        ft.color.getBlue(), (int)(ft.alpha * 255)));
                g.drawString(ft.text, tx, ty);
            }
        }
    }

    // ── Status bar ────────────────────────────────────────────────────────────
    private void drawStatusBar(Graphics2D g) {
        int sy = HUD_H + ROWS * CELL;
        g.setColor(STATUS_BG);
        g.fillRect(0, sy, W, STATUS_H);
        g.setColor(new Color(60, 60, 90));
        g.fillRect(0, sy, W, 2);

        Player me = findPlayer(myPlayerId);
        if (me == null) return;

        // Left: name + score
        g.setFont(new Font("Monospaced", Font.BOLD, 13));
        g.setColor(me.color);
        g.drawString(me.name, 14, sy + 20);
        int nx = 14 + g.getFontMetrics().stringWidth(me.name) + 16;
        g.setColor(Color.WHITE);
        g.drawString("Score: " + me.score, nx, sy + 20);
        nx += g.getFontMetrics().stringWidth("Score: " + me.score) + 20;

        // Status badges
        if (me.frozen)    { drawBadge(g, "FROZEN",      new Color(100, 200, 255), nx, sy + 8); nx += 90;  }
        if (me.hasWeapon) { drawBadge(g, "FREEZE RAY",  new Color(0, 210, 240),   nx, sy + 8); nx += 120; }
        if (me.speedBoostUntil > System.currentTimeMillis()) {
            drawBadge(g, "SPEED BOOST", new Color(220, 80, 255), nx, sy + 8);
            nx += 130;
        }

        // Right: Cooldown indicator
        drawCooldownIndicator(g, sy);
    }

    // ── Cooldown indicator ────────────────────────────────────────────────────
    private void drawCooldownIndicator(Graphics2D g, int sy) {
        long elapsed  = System.currentTimeMillis() - lastFiredTime;
        boolean ready = elapsed >= COOLDOWN_MS || lastFiredTime == 0;
        float progress = ready ? 1f : (float) elapsed / COOLDOWN_MS;

        int bx = W - 170, by = sy + 8, bw = 160, bh = 32;

        // Background
        g.setColor(new Color(20, 20, 35));
        g.fillRoundRect(bx, by, bw, bh, 8, 8);

        // Progress bar fill
        Color barColor = ready ? new Color(0, 220, 120) : new Color(80, 130, 200);
        g.setColor(barColor);
        g.fillRoundRect(bx, by, (int)(bw * progress), bh, 8, 8);

        // Border
        g.setColor(ready ? new Color(0, 200, 100) : new Color(60, 100, 180));
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(bx, by, bw, bh, 8, 8);
        g.setStroke(new BasicStroke(1f));

        // Label
        g.setFont(new Font("Monospaced", Font.BOLD, 12));
        String label = ready ? "FREEZE RAY  READY" :
                String.format("COOLDOWN  %.1fs", (COOLDOWN_MS - elapsed) / 1000f);
        g.setColor(ready ? Color.WHITE : new Color(180, 210, 255));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(label, bx + (bw - fm.stringWidth(label)) / 2, by + 21);
    }

    private void drawBadge(Graphics2D g, String text, Color color, int x, int y) {
        g.setFont(new Font("Monospaced", Font.BOLD, 11));
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text) + 10;
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 60));
        g.fillRoundRect(x, y, w, 20, 6, 6);
        g.setColor(color);
        g.setStroke(new BasicStroke(1.2f));
        g.drawRoundRect(x, y, w, 20, 6, 6);
        g.setStroke(new BasicStroke(1f));
        g.drawString(text, x + 5, y + 14);
    }

    // ── Game Over overlay ─────────────────────────────────────────────────────
    private void drawGameOverOverlay(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(0, HUD_H, W, ROWS * CELL);
        g.setFont(new Font("Monospaced", Font.BOLD, 52));
        g.setColor(new Color(255, 220, 50));
        String title = "GAME OVER";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(title, (W - fm.stringWidth(title)) / 2, HUD_H + ROWS * CELL / 2 - 20);
        if (gameState.winnerId != null) {
            Player winner = findPlayer(gameState.winnerId);
            if (winner != null) {
                g.setFont(new Font("Monospaced", Font.BOLD, 22));
                g.setColor(winner.color);
                String winLine = winner.name + " WINS!";
                fm = g.getFontMetrics();
                g.drawString(winLine, (W - fm.stringWidth(winLine)) / 2, HUD_H + ROWS * CELL / 2 + 28);
            }
        }
    }

    private void showGameOver(GameState state) {
        StringBuilder sb = new StringBuilder("Final Scores\n\n");
        List<Player> sorted = new ArrayList<>(state.players);
        sorted.sort((a, b) -> b.score - a.score);
        for (Player p : sorted) {
            sb.append(p.name).append(": ").append(p.score).append(" pts");
            if (p.playerId.equals(state.winnerId)) sb.append("  <- WINNER");
            sb.append("\n");
        }
        JOptionPane.showMessageDialog(this, sb.toString(), "ChronoArena — Game Over",
                JOptionPane.INFORMATION_MESSAGE);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private Player findPlayer(String id) {
        if (gameState == null || id == null) return null;
        for (Player p : gameState.players) if (p.playerId.equals(id)) return p;
        return null;
    }

    private Player findPlayerInState(GameState state, String id) {
        if (state == null || id == null) return null;
        for (Player p : state.players) if (p.playerId.equals(id)) return p;
        return null;
    }
}
