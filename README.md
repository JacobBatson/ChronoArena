# ChronoArena

A real-time multiplayer arena game built in Java. Up to 4 players compete on a grid-based map, capturing zones and collecting items to earn the highest score before time runs out.

**Team Members:** Jacob Batson, Kaab Dawit, Ben Delton, Thomas Mitropoulos

---

## How to Run

### 1. Compile
```bash
javac -d out src/shared/*.java src/server/GameEngine.java src/server/LobbyPanel.java src/server/ClientHandler.java src/server/ServerMain.java src/client/*.java
```

### 2. Start the Server
Run this on one machine:
```bash
java -cp out server.ServerMain
```
A lobby window will open. Players connect first, then the operator sets the duration and clicks **Start Game**.

### 3. Connect Clients
Each player runs this on their own machine:
```bash
java -cp out client.ClientMain
```
- Enter the server's IP address when prompted
- Enter your player name
- Up to 4 players can connect

All players must be on the same network. Find the server's IP with:
```bash
ipconfig getifaddr en0   # macOS
ipconfig                 # Windows
```

---

## How to Play

| Key | Action |
|-----|--------|
| WASD or Arrow Keys | Move |
| SPACE | Fire Freeze Ray (if you have it) |

---

## Game Rules

**Goal:** Have the highest score when the timer runs out.

### Scoring
| Action | Points |
|--------|--------|
| Own a zone | +2 per second (passive) |
| Pick up Energy item | +10 instantly |
| Freeze an enemy | Enemy loses -10 |

### Zones (A, B, C)
- Walk into a zone and stay to capture it (~3 seconds)
- Once owned, it drips points into your score automatically
- If an enemy enters your zone it becomes **CONTESTED** — no progress either way
- If you leave your zone, you have a **5-second grace period** before losing it
- An unchallenged enemy in your zone will slowly steal it

### Items
| Item | Color | Effect |
|------|-------|--------|
| E — Energy | Gold | +10 points instantly |
| F — Freeze Ray | Cyan | Gives you the freeze weapon |
| S — Speed Boost | Purple | Move faster temporarily |

### Freeze Ray
- Pick up the **F** item to arm your freeze ray
- Press **SPACE** to fire — freezes the nearest enemy within 2 cells
- Frozen enemy loses 10 points and cannot move for 3 seconds
- You lose the weapon after firing (5-second cooldown)

---

## Architecture

```
┌─────────────────────────────┐
│         SERVER              │
│  ServerMain (entry point)   │
│  LobbyPanel (pre-game UI)   │
│  GameEngine (game loop)     │
│  ClientHandler × N (TCP)    │
│  UDP receiver (actions)     │
└──────────┬──────────────────┘
           │  TCP: GameState broadcast (every tick)
           │  UDP: PlayerAction (move/freeze)
┌──────────┴──────────────────┐
│         CLIENT              │
│  ClientMain (entry point)   │
│  GamePanel (Swing GUI)      │
│  ServerListener (TCP recv)  │
└─────────────────────────────┘
```

- **TCP** — reliable channel: join handshake, game state broadcasts, kill messages
- **UDP** — low-latency channel: player movement and actions sent every keypress
- **Game loop** — server ticks every 100ms; all actions queued and processed per tick
- **Sequence numbers** — UDP packets are deduplicated and ordered to handle network jitter
- **Server authoritative** — all game logic runs on the server; clients only render what they receive

### Key Files
| File | Description |
|------|-------------|
| `src/shared/` | Serializable data models shared by client and server |
| `src/server/GameEngine.java` | Game loop, zone logic, item spawning, scoring, freeze mechanics |
| `src/server/LobbyPanel.java` | Server lobby UI — shows connected players, sets game duration |
| `src/server/ClientHandler.java` | Per-client TCP thread — broadcasts GameState each tick |
| `src/server/ServerMain.java` | Server entry point — TCP accept loop, UDP receiver, kill switch |
| `src/client/GamePanel.java` | Swing GUI — renders grid, zones, players, items, HUD |
| `src/client/ClientMain.java` | Client entry point — connects to server, sends UDP actions |
| `src/client/ServerListener.java` | TCP listener thread — receives GameState and updates GUI |

---

## Configuration

Edit `config.properties` to change server settings:

```properties
# Change to the server machine's IP address when playing on multiple computers
server.ip=localhost
server.tcp.port=12345
server.udp.port=12346
game.duration=150
```

---

## Server Kill Switch

While the server is running, type into the server terminal:
```
kill <playerId> [reason]
```
Example: `kill player1 AFK` — forcibly disconnects that player.
