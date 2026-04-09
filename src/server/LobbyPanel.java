package server;

import shared.GameState;
import shared.Player;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * LobbyPanel — shown on the server before the game starts.
 *
 * Lets the operator choose a game duration and see players as they connect.
 * Calls onStart (on the EDT) when the Start Game button is clicked.
 */
public class LobbyPanel extends JPanel implements GameEngine.Listener {

    private final JSpinner      durationSpinner;
    private final DefaultListModel<String> playerListModel = new DefaultListModel<>();
    private final JLabel        playerCountLabel;
    private final JButton       startButton;

    public LobbyPanel(Runnable onStart) {

        setBackground(new Color(15, 15, 30));
        setLayout(new GridBagLayout());
        setBorder(new EmptyBorder(40, 60, 40, 60));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(10, 10, 10, 10);
        c.fill   = GridBagConstraints.HORIZONTAL;
        c.gridx  = 0;

        // ── Title ─────────────────────────────────────────────────────────────
        JLabel title = new JLabel("ChronoArena", SwingConstants.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 36));
        title.setForeground(new Color(120, 180, 255));
        c.gridy = 0;
        add(title, c);

        JLabel subtitle = new JLabel("Server Lobby", SwingConstants.CENTER);
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 18));
        subtitle.setForeground(new Color(160, 160, 180));
        c.gridy = 1;
        add(subtitle, c);

        // ── Separator ─────────────────────────────────────────────────────────
        JSeparator sep = new JSeparator();
        sep.setForeground(new Color(60, 60, 80));
        c.gridy = 2;
        add(sep, c);

        // ── Duration picker ───────────────────────────────────────────────────
        JPanel durationRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        durationRow.setBackground(getBackground());

        JLabel durationLabel = new JLabel("Game Duration:");
        durationLabel.setForeground(Color.WHITE);
        durationLabel.setFont(new Font("SansSerif", Font.PLAIN, 16));

        durationSpinner = new JSpinner(new SpinnerNumberModel(2, 1, 10, 1));
        durationSpinner.setFont(new Font("SansSerif", Font.BOLD, 16));
        durationSpinner.setPreferredSize(new Dimension(70, 32));

        JLabel minsLabel = new JLabel("minutes");
        minsLabel.setForeground(new Color(160, 160, 180));
        minsLabel.setFont(new Font("SansSerif", Font.PLAIN, 16));

        durationRow.add(durationLabel);
        durationRow.add(durationSpinner);
        durationRow.add(minsLabel);

        c.gridy = 3;
        add(durationRow, c);

        // ── Player list ───────────────────────────────────────────────────────
        playerCountLabel = new JLabel("Connected Players (0):", SwingConstants.LEFT);
        playerCountLabel.setForeground(new Color(200, 200, 220));
        playerCountLabel.setFont(new Font("SansSerif", Font.BOLD, 15));
        c.gridy = 4;
        add(playerCountLabel, c);

        JList<String> playerList = new JList<>(playerListModel);
        playerList.setBackground(new Color(25, 25, 50));
        playerList.setForeground(Color.WHITE);
        playerList.setFont(new Font("SansSerif", Font.PLAIN, 15));
        playerList.setSelectionBackground(new Color(50, 50, 90));
        playerList.setBorder(new EmptyBorder(6, 10, 6, 10));

        JScrollPane scroll = new JScrollPane(playerList);
        scroll.setPreferredSize(new Dimension(300, 130));
        scroll.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 100)));
        c.gridy = 5;
        add(scroll, c);

        // ── Start button ──────────────────────────────────────────────────────
        startButton = new JButton("Start Game");
        startButton.setFont(new Font("SansSerif", Font.BOLD, 18));
        startButton.setBackground(new Color(50, 150, 80));
        startButton.setForeground(Color.WHITE);
        startButton.setFocusPainted(false);
        startButton.setEnabled(false);
        startButton.setPreferredSize(new Dimension(200, 46));
        startButton.addActionListener(e -> onStart.run());

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER));
        btnRow.setBackground(getBackground());
        btnRow.add(startButton);
        c.gridy = 6;
        add(btnRow, c);
    }

    /** Returns the selected duration in seconds. */
    public int getDurationSeconds() {
        return (int) durationSpinner.getValue() * 60;
    }

    // ── GameEngine.Listener ───────────────────────────────────────────────────

    @Override
    public void onTick(GameState state) {
        // Collect connected player names
        List<String> names = new ArrayList<>();
        for (Player p : state.players) {
            if (p.connected) names.add(p.name);
        }

        SwingUtilities.invokeLater(() -> {
            playerListModel.clear();
            for (String name : names) playerListModel.addElement(name);
            playerCountLabel.setText("Connected Players (" + names.size() + "):");
            startButton.setEnabled(!names.isEmpty());
        });
    }

    @Override
    public void onPlayerKilled(String playerId, String reason) {
        // Not expected during lobby, no-op
    }
}
