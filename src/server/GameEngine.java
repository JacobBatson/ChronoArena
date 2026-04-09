package server;

import shared.*;
import shared.Item.ItemType;

import java.awt.Color;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * GameEngine — the authoritative brain of ChronoArena.
 *
 * Runs a fixed-rate game loop (100 ms / tick).  All player actions
 * arrive via enqueueAction() from the UDP receiver thread and are
 * drained + processed once per tick, guaranteeing fairness.
 */
public class GameEngine {

    // ── Listener interface ────────────────────────────────────────────────────
    public interface Listener {
        /** Called at the end of every tick with the latest game state. */
        void onTick(GameState state);
        /** Called when a player is forcibly killed (KILL_SWITCH). */
        void onPlayerKilled(String playerId, String reason);
    }

    // ── Constants ─────────────────────────────────────────────────────────────
    public static final int COLS = 20;
    public static final int ROWS = 15;

    private static final int    TICK_MS                 = 100;   // ms per tick
    private static final int    CAPTURE_TICKS           = 30;    // ticks to fully capture
    private static final float  CAPTURE_RATE            = 1f / CAPTURE_TICKS;
    private static final long   FREEZE_DURATION_MS      = 3_000;
    private static final long   SPEED_BOOST_DURATION_MS = 3_000;
    private static final long   GRACE_PERIOD_MS         = 5_000;
    private static final int    FREEZE_RANGE            = 2;     // Manhattan distance
    private static final int    FREEZE_PENALTY          = 10;
    private static final int    ENERGY_BONUS            = 10;
    private static final int    ZONE_POINTS_INTERVAL    = 10;    // ticks → 1 s
    private static final int    ITEM_SPAWN_INTERVAL     = 30;    // ticks → 3 s
    private static final int    MAX_ITEMS               = 5;

    /** Fixed colours for the first 4 players. */
    private static final Color[] BASE_COLORS = {
        new Color(80,  130, 255),   // blue
        new Color(255, 80,  80),    // red
        new Color(80,  200, 80),    // green
        new Color(255, 160, 0)      // orange
    };

    // ── Fields ────────────────────────────────────────────────────────────────
    private final GameState                      gameState;
    private final ConcurrentLinkedQueue<PlayerAction> actionQueue = new ConcurrentLinkedQueue<>();
    private final List<Listener>                 listeners   = new ArrayList<>();
    private final Random                         random      = new Random();

    /** Server-side only: tracks which player is currently capturing each zone. */
    private final Map<String, String> zoneCapturer = new HashMap<>();

    private int              playerIdCounter = 1;
    private int              itemIdCounter   = 1;
    private boolean          running         = false;
    private volatile boolean gameStarted     = false;

    // ── Constructor ───────────────────────────────────────────────────────────
    public GameEngine(int gameDurationSeconds) {
        gameState = new GameState(gameDurationSeconds);
        initZones();
    }

    /** Place three zones at random non-overlapping positions. */
    private void initZones() {
        String[] ids = {"A", "B", "C"};
        for (String id : ids) {
            int[] pos = randomZonePosition();
            gameState.zones.add(new Zone(id, pos[0], pos[1], 3, 3));
        }
    }

    /**
     * Picks a random top-left corner for a 3×3 zone that doesn't overlap
     * (with a 1-cell buffer) any zone already in gameState.zones.
     */
    private int[] randomZonePosition() {
        for (int attempt = 0; attempt < 200; attempt++) {
            int x = 1 + random.nextInt(COLS - 4); // [1, COLS-4]
            int y = 1 + random.nextInt(ROWS - 4); // [1, ROWS-4]
            if (!overlapsExistingZone(x, y)) return new int[]{x, y};
        }
        // Fallback: evenly spaced columns, middle row
        int idx = gameState.zones.size();
        return new int[]{2 + idx * 6, ROWS / 2 - 1};
    }

    /** Returns true if a 3×3 zone at (x,y) overlaps any existing zone (with 1-cell buffer). */
    private boolean overlapsExistingZone(int x, int y) {
        for (Zone z : gameState.zones) {
            if (x < z.x + z.width  + 1 && x + 3 + 1 > z.x &&
                y < z.y + z.height + 1 && y + 3 + 1 > z.y) {
                return true;
            }
        }
        return false;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void addListener(Listener l)    { listeners.add(l); }

    /** Returns a reference to the live game state (read-only from outside). */
    public GameState getGameState()        { return gameState; }

    /** Enqueues an action from the UDP receiver thread. Thread-safe. */
    public void enqueueAction(PlayerAction action) { actionQueue.offer(action); }

    /**
     * Adds a new player to the game.
     * Called by ClientHandler when a JOIN message arrives over TCP.
     */
    public synchronized Player addPlayer(String name) {
        int slot  = gameState.players.size();
        int[] pos = spawnPosition();
        Color color = slot < BASE_COLORS.length
                ? BASE_COLORS[slot]
                : Color.getHSBColor(slot / 8f, 0.75f, 1f);

        String id = "p" + playerIdCounter++;
        Player p  = new Player(id, name, pos[0], pos[1], color);
        gameState.players.add(p);
        System.out.println("[Engine] Player joined: " + name + " (" + id + ")");
        return p;
    }

    /** Returns a random spawn position that is not inside a zone and not occupied. */
    private int[] spawnPosition() {
        for (int attempt = 0; attempt < 200; attempt++) {
            int x = random.nextInt(COLS);
            int y = random.nextInt(ROWS);
            if (!isCellOccupiedByPlayer(x, y) && !isCellInAnyZone(x, y)) return new int[]{x, y};
        }
        // Fallback: place anywhere not occupied
        return new int[]{random.nextInt(COLS), random.nextInt(ROWS)};
    }

    /** Returns true if (x,y) falls inside any zone's rectangle. */
    private boolean isCellInAnyZone(int x, int y) {
        for (Zone z : gameState.zones) {
            if (x >= z.x && x < z.x + z.width && y >= z.y && y < z.y + z.height) return true;
        }
        return false;
    }

    /**
     * Marks a player as disconnected (graceful leave or network drop).
     * Their zone ownership starts a grace timer immediately.
     */
    public synchronized void removePlayer(String playerId) {
        Player p = findPlayer(playerId);
        if (p == null) return;
        p.connected = false;
        System.out.println("[Engine] Player disconnected: " + p.name);

        // Start grace timer on any zone they own
        long now = System.currentTimeMillis();
        for (Zone z : gameState.zones) {
            if (playerId.equals(z.ownerId) && z.graceTimerExpiry < 0) {
                z.graceTimerExpiry = now + GRACE_PERIOD_MS;
            }
        }
    }

    /**
     * KILL_SWITCH — forcibly disconnects a player and notifies all listeners.
     * Called from the server console: "kill <playerId>"
     */
    public void killPlayer(String playerId, String reason) {
        removePlayer(playerId);
        for (Listener l : listeners) {
            l.onPlayerKilled(playerId, reason);
        }
        System.out.println("[Engine] KILL_SWITCH: player " + playerId + " removed. Reason: " + reason);
    }

    /** Starts the game loop on a dedicated daemon thread. */
    public void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::runLoop, "GameLoop");
        t.setDaemon(true);
        t.start();
        System.out.println("[Engine] Game loop started.");
    }

    /** Stops the game loop cleanly. */
    public void stop() {
        running = false;
    }

    /** Unpauses the game — timer and scoring begin. Call when the operator clicks Start. */
    public void startGame() {
        gameStarted = true;
        System.out.println("[Engine] Game started.");
    }

    /** Updates the game duration. Safe to call before startGame(). */
    public void setDuration(int seconds) {
        gameState.timeRemaining = seconds;
    }

    // ── Game Loop ─────────────────────────────────────────────────────────────

    private void runLoop() {
        while (running && !gameState.gameOver) {
            long tickStart = System.currentTimeMillis();

            tick();

            long elapsed = System.currentTimeMillis() - tickStart;
            long sleep   = TICK_MS - elapsed;
            if (sleep > 0) {
                try { Thread.sleep(sleep); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        System.out.println("[Engine] Game loop ended. Game over: " + gameState.gameOver);
    }

    /**
     * One game tick — the heart of the engine.
     * All logic runs here in a single thread so there are no race conditions
     * on the game state itself.
     */
    private synchronized void tick() {
        // 1. Drain ALL queued actions into a local list (snapshot)
        List<PlayerAction> actions = new ArrayList<>();
        PlayerAction a;
        while ((a = actionQueue.poll()) != null) actions.add(a);

        // 2. Process MOVE actions first
        for (PlayerAction action : actions) {
            if (action.type == ActionType.MOVE) processMove(action);
        }

        // 3. Process FREEZE / USE_ITEM actions
        for (PlayerAction action : actions) {
            if (action.type == ActionType.FREEZE || action.type == ActionType.USE_ITEM)
                processFreeze(action);
        }

        // 4. Zone capture logic
        updateZones();

        // 5. Item pickups
        checkItemPickups();

        // 6. Frozen / speed-boost timers
        updateTimers();

        // 7. Every 10 ticks (1 second): zone points + timer countdown (only after game started)
        if (gameStarted && gameState.currentTick % ZONE_POINTS_INTERVAL == 0 && gameState.currentTick > 0) {
            awardZonePoints();
            decrementTimer();
        }

        // 8. Every 30 ticks (3 seconds): try to spawn an item
        if (gameState.currentTick % ITEM_SPAWN_INTERVAL == 0 && gameState.currentTick > 0) {
            spawnItem();
        }

        // 9. Advance tick counter
        gameState.currentTick++;

        // 10. Check game over (only after game started)
        if (gameStarted) checkGameOver();

        // Propagate lobby/started state to clients
        gameState.gameStarted = gameStarted;

        // 11. Notify all listeners (ClientHandlers broadcast to clients)
        for (Listener l : listeners) l.onTick(gameState);
    }

    // ── Action Processing ─────────────────────────────────────────────────────

    /**
     * Validates and applies a MOVE action.
     * Rejects out-of-order / duplicate packets using sequence numbers.
     */
    private void processMove(PlayerAction action) {
        Player p = findPlayer(action.playerId);
        if (p == null || !p.connected || p.frozen) return;

        // Out-of-order / duplicate packet check
        if (action.seqNumber <= p.lastSeqNumber) return;
        p.lastSeqNumber = action.seqNumber;

        // Speed boost: move 2 cells instead of 1
        int step = (System.currentTimeMillis() < p.speedBoostUntil) ? 2 : 1;
        int newX = Math.max(0, Math.min(COLS - 1, p.gridX + action.dx * step));
        int newY = Math.max(0, Math.min(ROWS - 1, p.gridY + action.dy * step));

        p.gridX = newX;
        p.gridY = newY;
    }

    /**
     * Validates and applies a FREEZE (USE_ITEM) action.
     * Player must have the weapon. Finds the nearest enemy within FREEZE_RANGE.
     * First player in queue wins if two freeze at the same tick (FIFO fairness).
     */
    private void processFreeze(PlayerAction action) {
        Player attacker = findPlayer(action.playerId);
        if (attacker == null || !attacker.connected || attacker.frozen || !attacker.hasWeapon) return;

        // Out-of-order / duplicate packet check
        if (action.seqNumber <= attacker.lastSeqNumber) return;
        attacker.lastSeqNumber = action.seqNumber;

        // Find nearest connected, non-frozen enemy within range
        Player target    = null;
        int    minDist   = Integer.MAX_VALUE;
        for (Player p : gameState.players) {
            if (p.playerId.equals(attacker.playerId) || !p.connected) continue;
            int dist = Math.abs(p.gridX - attacker.gridX) + Math.abs(p.gridY - attacker.gridY);
            if (dist <= FREEZE_RANGE && dist < minDist) {
                minDist = dist;
                target  = p;
            }
        }

        if (target != null) {
            target.frozen      = true;
            target.frozenUntil = System.currentTimeMillis() + FREEZE_DURATION_MS;
            target.score       = Math.max(0, target.score - FREEZE_PENALTY);
            attacker.hasWeapon = false;
            System.out.println("[Engine] " + attacker.name + " froze " + target.name);
        }
    }

    // ── Zone Logic ────────────────────────────────────────────────────────────

    /**
     * Core zone capture / contest / grace-timer logic.
     * Runs once per tick after all move actions are processed.
     */
    private void updateZones() {
        long now = System.currentTimeMillis();

        for (Zone z : gameState.zones) {
            List<Player> inside = playersInZone(z);

            if (inside.isEmpty()) {
                // ── Nobody in the zone ──────────────────────────────────────
                z.contestedBy = null;

                if (z.ownerId != null) {
                    if (z.graceTimerExpiry < 0) {
                        // Owner just left → start grace timer
                        z.graceTimerExpiry = now + GRACE_PERIOD_MS;
                    } else if (now > z.graceTimerExpiry) {
                        // Grace timer expired → owner loses the zone
                        System.out.println("[Engine] Zone " + z.zoneId + " lost by " + z.ownerId + " (grace expired)");
                        z.ownerId          = null;
                        z.captureProgress  = 0f;
                        z.graceTimerExpiry = -1;
                        zoneCapturer.remove(z.zoneId);
                    }
                }

            } else if (inside.size() == 1) {
                // ── Exactly one player in the zone ─────────────────────────
                Player p = inside.get(0);
                z.contestedBy = null;

                boolean isOwner = p.playerId.equals(z.ownerId);

                if (isOwner) {
                    // Owner is back → cancel grace timer
                    z.graceTimerExpiry = -1;
                    zoneCapturer.remove(z.zoneId);
                } else {
                    // Non-owner is capturing
                    // Cancel grace timer (challenger is actively taking the zone)
                    z.graceTimerExpiry = -1;

                    // If the capturer changed, reset progress
                    String prev = zoneCapturer.get(z.zoneId);
                    if (!p.playerId.equals(prev)) {
                        z.captureProgress = 0f;
                        zoneCapturer.put(z.zoneId, p.playerId);
                    }

                    z.captureProgress = Math.min(1f, z.captureProgress + CAPTURE_RATE);

                    if (z.captureProgress >= 1f) {
                        // Zone captured!
                        System.out.println("[Engine] Zone " + z.zoneId + " captured by " + p.name);
                        z.ownerId          = p.playerId;
                        z.captureProgress  = 1f;
                        z.graceTimerExpiry = -1;
                        zoneCapturer.remove(z.zoneId);
                    }
                }

            } else {
                // ── Two or more players in the zone → CONTESTED ────────────
                z.contestedBy      = "CONTESTED";
                z.graceTimerExpiry = -1; // suspend grace timer while contested

                // Check if owner is among those inside — if so cancel grace timer
                boolean ownerPresent = inside.stream().anyMatch(p -> p.playerId.equals(z.ownerId));
                if (ownerPresent) z.graceTimerExpiry = -1;

                // Capture progress stalls — do nothing
            }
        }
    }

    // ── Item Pickups ──────────────────────────────────────────────────────────

    /**
     * Checks if any player is standing on a collectible item.
     * FIFO fairness: players are checked in the order they joined,
     * so the first player in the list wins a tie.
     */
    private void checkItemPickups() {
        for (Player p : gameState.players) {
            if (!p.connected || p.frozen) continue;
            for (Item item : gameState.items) {
                if (item.collected) continue;
                if (item.gridX == p.gridX && item.gridY == p.gridY) {
                    collectItem(p, item);
                }
            }
        }
        // Remove collected items from the list
        gameState.items.removeIf(item -> item.collected);
    }

    private void collectItem(Player p, Item item) {
        item.collected = true;
        switch (item.type) {
            case ENERGY:
                p.score += ENERGY_BONUS;
                System.out.println("[Engine] " + p.name + " collected ENERGY (+10)");
                break;
            case FREEZE_RAY:
                p.hasWeapon = true;
                System.out.println("[Engine] " + p.name + " picked up FREEZE RAY");
                break;
            case SPEED_BOOST:
                p.speedBoostUntil = System.currentTimeMillis() + SPEED_BOOST_DURATION_MS;
                System.out.println("[Engine] " + p.name + " got SPEED BOOST");
                break;
        }
    }

    // ── Timer Updates ─────────────────────────────────────────────────────────

    /** Unfreezes players whose freeze duration has expired. */
    private void updateTimers() {
        long now = System.currentTimeMillis();
        for (Player p : gameState.players) {
            if (p.frozen && now >= p.frozenUntil) {
                p.frozen = false;
                System.out.println("[Engine] " + p.name + " unfrozen.");
            }
        }
    }

    /** Awards passive zone points (+2) to each zone owner. Called every 10 ticks (1 sec). */
    private void awardZonePoints() {
        for (Zone z : gameState.zones) {
            if (z.ownerId == null) continue;
            Player owner = findPlayer(z.ownerId);
            if (owner != null && owner.connected) {
                owner.score += z.pointsPerSecond;
            }
        }
    }

    /** Decrements the game timer by 1 second. Called every 10 ticks. */
    private void decrementTimer() {
        if (gameState.timeRemaining > 0) gameState.timeRemaining--;
    }

    // ── Item Spawning ─────────────────────────────────────────────────────────

    /** Attempts to spawn one random item at a free cell. */
    private void spawnItem() {
        if (getActiveItemCount() >= MAX_ITEMS) return;

        ItemType[] types = ItemType.values();
        ItemType   type  = types[random.nextInt(types.length)];

        // Try up to 50 random positions to find a free cell
        for (int attempt = 0; attempt < 50; attempt++) {
            int x = random.nextInt(COLS);
            int y = random.nextInt(ROWS);
            if (isCellFreeOfItems(x, y) && !isCellOccupiedByPlayer(x, y)) {
                gameState.items.add(new Item("item_" + itemIdCounter++, x, y, type));
                return;
            }
        }
    }

    // ── Game Over ─────────────────────────────────────────────────────────────

    private void checkGameOver() {
        if (gameState.timeRemaining > 0) return;

        gameState.gameOver = true;
        running = false;

        // Winner = connected player with highest score
        Player winner = null;
        for (Player p : gameState.players) {
            if (p.connected && (winner == null || p.score > winner.score)) {
                winner = p;
            }
        }
        gameState.winnerId = (winner != null) ? winner.playerId : null;

        String winName = (winner != null) ? winner.name : "Nobody";
        System.out.println("[Engine] Game over! Winner: " + winName);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Finds a player by ID. Returns null if not found. */
    public Player findPlayer(String playerId) {
        for (Player p : gameState.players) {
            if (p.playerId.equals(playerId)) return p;
        }
        return null;
    }

    /** Returns all connected players currently inside the zone's grid rectangle. */
    private List<Player> playersInZone(Zone z) {
        List<Player> result = new ArrayList<>();
        for (Player p : gameState.players) {
            if (!p.connected) continue;
            if (p.gridX >= z.x && p.gridX < z.x + z.width &&
                p.gridY >= z.y && p.gridY < z.y + z.height) {
                result.add(p);
            }
        }
        return result;
    }

    private int getActiveItemCount() {
        int count = 0;
        for (Item item : gameState.items) if (!item.collected) count++;
        return count;
    }

    private boolean isCellFreeOfItems(int x, int y) {
        for (Item item : gameState.items) {
            if (!item.collected && item.gridX == x && item.gridY == y) return false;
        }
        return true;
    }

    private boolean isCellOccupiedByPlayer(int x, int y) {
        for (Player p : gameState.players) {
            if (p.connected && p.gridX == x && p.gridY == y) return true;
        }
        return false;
    }
}
