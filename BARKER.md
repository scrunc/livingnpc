# LivingShopkeeper — Barker & Character-Type Design

The full technical plan for typed characters, per-character folders, and the **barker** — a shop crier that
patrols an ARM region (walking via **A\*** pathfinding) and lures passers-by inside. Decision record:
[ADR 0030](../../ai-continue/decisions/0030-livingshopkeeper-barker-character-types.md).

Locked choices: **A\* movement**, **tight ARM integration**, **build B0–B4 all at once** (one test at end).

---

## 0. Package map (new + changed)

```
dev.servereer.livingshopkeeper
├── character/
│   ├── Character              # was Shopkeeper: id, uuid, type, skin, world, + type config; folder I/O
│   ├── CharacterType          # SHOPKEEPER | BARKER | INFORMER
│   ├── CharacterManager       # was ShopkeeperManager: load folders, render/track, dispatch by type
│   ├── Behavior               # interface: onLoad/onTick/onInteract/onRemove per character
│   ├── ShopkeeperBehavior     # the P1–P3 behavior, unchanged, behind the interface
│   └── BarkerBehavior         # the new one (FSM below)
├── move/
│   ├── PathNavigator          # block A*: findPath(from,to) -> List<Vec>, bounded + cached
│   ├── NavMeshSampler         # walkable-node test + neighbour expansion (step up/down)
│   ├── MovementController     # follow a path: step/tick, relative-move + head-look packets, arrival
│   └── MovePackets            # 1.21.11 move/teleport/head-look wrappers (verified at build)
├── region/
│   ├── ShopRegion             # abstraction: contains(loc) · distanceTo(loc) · bounds · owner
│   ├── ArmRegionSource        # AdvancedRegionMarket-backed (primary)
│   ├── WorldGuardRegionSource # WG-backed fallback
│   └── RadiusRegionSource     # plain radius/cuboid fallback (no deps)
├── barker/
│   ├── BarkerState            # PATROL | INVITING | RETURNING  (+ per-barker runtime)
│   ├── ProspectTracker        # who's a candidate, per-player cooldowns (attracted / ignored)
│   └── BarkerStateStore       # persist attract-cooldowns -> characters/<name>/state.yml
├── audio/  (P2)   ├── dialog/ (P3)   ├── npc/PacketNpc (P1, reused for render)
```

---

## 1. Character folders (B0)

```
plugins/LivingShopkeeper/characters/<name>/
├── character.yml     # identity + type + type-specific config
├── dialog/*.yml       # this character's dialog packs
└── voice/*.wav        # this character's clips (ElevenLabs → WAV)
```

**`character.yml` (shopkeeper):**
```yaml
type: shopkeeper
name: "Bruno the Blacksmith"
skin: Notch                     # player name / texture (as today)
world: world
location: { x: 100.5, y: 64, z: 200.5, yaw: 90, pitch: 0 }
dialog: { pack: default }       # -> characters/<name>/dialog/default.yml
```

**`character.yml` (barker):**
```yaml
type: barker
name: "Cassie the Crier"
skin: Cassie
world: world
home: { x: 100.5, y: 64, z: 210.5, yaw: 0, pitch: 0 }   # where it idles / returns to
region: my_shop                 # ARM region id (see §3)
waypoints:                       # patrol route (walked via A*)
  - { x: 100, y: 64, z: 205 }
  - { x: 108, y: 64, z: 205 }
  - { x: 108, y: 64, z: 214 }
dialog: { pack: default }        # nodes for triggers: invite / invite_success / invite_giveup
barker:                          # all optional; defaults below
  detect-radius: 5               # blocks OUTSIDE the region to notice a passer-by
  invite-tries: 3
  invite-delay: 10s
  attracted-cooldown: 3.5h       # stop inviting a player who entered, this long
  ignored-cooldown: 8m           # brief backoff for a player who ignored the invite
  walk-speed: 4.3                # blocks/sec
  max-chase: 12                  # never wander more than this from home while inviting
  waypoint-pause: 3s             # dwell at each patrol point
```

**Migration:** on load, any legacy `shopkeepers/<id>.yml` is moved to `characters/<id>/character.yml`
(`type: shopkeeper`), its `dialog.pack` clip refs resolved from the (now shared→copied) voice folder. The
old flat `dialog/` + `voice/` stay as a shared fallback so nothing 404s mid-migration.

**Model:** `Character` replaces `Shopkeeper` — same render fields (uuid, entityId, viewers, skin, look-at)
plus `type` and a `Behavior`. `CharacterManager` owns the scan loop (render/track/look-at, unchanged) and
calls `behavior.onTick()` / `onInteract()` so each type adds its own logic. `Shopkeeper` P1–P3 logic becomes
`ShopkeeperBehavior` verbatim.

**Voice namespacing:** `VoiceBank` keys clips as `<character>/<clip>`; `SpeechController.speak` takes the
character so `characters/cassie/voice/invite_1.wav` and `characters/bruno/voice/invite_1.wav` don't collide.
`DialogLibrary` loads each character's own `dialog/` folder.

---

## 2. Movement — A\* (B1, the hard part)

### 2.1 NavMesh sampling (`NavMeshSampler`)
A **node** is a block column position `(x, y, z)` that is *standable*: the block at `y-1` is solid (floor),
and `y`, `y+1` are passable (air/non-solid) for the NPC's ~2-block height. Neighbours of a node:
- 4 cardinals + 4 diagonals at the same `y`;
- **step-up 1** (target floor one higher, with head clearance);
- **step-down** up to 3 (fall), cost-weighted;
- diagonals require both orthogonal blocks passable (no corner-cutting through walls).

### 2.2 A\* (`PathNavigator`)
- Open set = priority queue on `f = g + h`; `h` = **octile distance** (8-dir admissible).
- Move cost 1.0 cardinal, √2 diagonal, +penalty for step-up/down.
- **Bounded:** `max-nodes` (e.g. 2 000) and `max-range` (e.g. 48 blocks) — bail to "no path"/partial path
  rather than search forever. Runs **off the main thread** (block reads snapshotted first) so a search can't
  stall the tick; result applied back on the main thread.
- **Cache:** last path per barker; only re-path when the target moves > k blocks or the follower gets stuck.
- **Fallback:** no path within bounds → straight-line step toward target (degrades gracefully, never freezes).

### 2.3 Following (`MovementController`)
Each movement tick (every 1–2 ticks):
- Advance a cursor along the path by `walk-speed / 20` blocks; set NPC yaw toward motion, pitch level.
- Emit a **relative-move + head-look** packet to current viewers (client animates the walk cycle from the
  positional delta). Ground-snap Y each step.
- On reaching the final node (within ε) → `onArrive()`.
- **Stuck detection:** no net progress over N ticks → re-path (or give up that leg).

`MovePackets` wraps the exact 1.21.11 packet — relative-move vs the newer `EntityPositionSync` — **verified
by `javap` on the cached packetevents jar** at build time (same rigor as the P1 spawn packets).

---

## 3. Region — ARM (B2)

`ShopRegion` abstraction with three sources tried in order:

1. **`ArmRegionSource` (primary):** compiled against the local `Advanced Region Market 3.5.5.jar`
   (Gradle `compileOnly files(...)`), every call guarded so a missing ARM never throws. Gives: resolve region
   by id, `contains(loc)`, `distanceTo(loc)` (to the region box), min/max bounds, and **owner UUID** (for the
   optional "auto-set up your own barker" UX). ARM regions wrap WorldGuard, so geometry comes from the WG
   region underneath.
2. **`WorldGuardRegionSource`:** if ARM is absent but WG is present, treat `region:` as a WG region id.
3. **`RadiusRegionSource`:** no WG/ARM → `region: { center: home, radius: N }`.

Two queries the barker needs: **inside?** (`contains`) and **near?** (`distanceTo ≤ detect-radius` while
`!contains`).

---

## 4. Barker behavior — the FSM (B3)

Per-barker runtime state machine, ticked on the shared scan:

```
PATROL: walk waypoint→waypoint (A*), dwell `waypoint-pause` at each.
        every decision-tick, scan region-adjacent players for a PROSPECT.
        prospect found ─────────────────────────────────► INVITING(target)

INVITING(target):
        walk to the region-edge point nearest the target (A*, capped at `max-chase` from home),
        face them, speak `invite` line (voiced).  wait `invite-delay`, repeat up to `invite-tries`.
        ├─ target enters region ──► SUCCESS: `invite_success` line;
        │                            attracted[target] = now + attracted-cooldown;  ► RETURNING
        ├─ target leaves detect range OR tries exhausted ──► GIVE_UP:
        │                            `invite_giveup` (optional); ignored[target] = now + ignored-cooldown; ► RETURNING
        └─ (only one target at a time; nearest prospect wins)

RETURNING: A* back toward `home`/nearest waypoint ──► PATROL.
```

**Prospect** = same-world player, **outside** the region, `distanceTo(region) ≤ detect-radius`, and
`now > max(attracted[p], ignored[p])`, and not the current target. Nearest wins.

**`ProspectTracker`** holds the per-player `attracted` / `ignored` maps; **`BarkerStateStore`** persists
`attracted` to `characters/<name>/state.yml` (survives restart — the 3–4h must). `ignored` is transient.

**Dialog reuse:** the barker fires `invite`, `invite_success`, `invite_giveup` through the P3 `DialogEngine`
(same weighting/cooldown/voice) — authored in the barker's own `dialog/`:
```yaml
nodes:
  call:  { triggers: [invite],         cooldown: 0s, lines: [ {text:"Oi! Fresh deals inside!", voice: invite_1} ] }
  won:   { triggers: [invite_success], lines: [ {text:"Welcome in, friend!", voice: welcome_1} ] }
  lost:  { triggers: [invite_giveup],  lines: [ {text:"Suit yourself…", voice: giveup_1} ] }
```

---

## 5. Commands (B4)

- `/lsk create <name> [skin]` — makes a **shopkeeper** (as today).
- `/lsk create <name> barker [skin]` — makes a **barker** at your location (home).
- `/lsk barker <name> region <armRegionId>`
- `/lsk barker <name> addwaypoint` (your position) · `waypoints` · `clearwaypoints`
- `/lsk barker <name> home` (set idle/return point)
- Existing: `remove · list · tp · skin · reload · version` (now type-aware).

Everything is also editable in `character.yml` + `/lsk reload`.

---

## 6. Config (global defaults; per-barker `barker:` overrides)

`config.yml` gains a `barker:` block mirroring the per-character keys in §1 (detect-radius, invite-tries,
invite-delay, attracted-cooldown, ignored-cooldown, walk-speed, max-chase, waypoint-pause) + navigator caps
(`nav.max-nodes`, `nav.max-range`, `nav.repath-threshold`).

---

## 7. Build order (delivered together, constructed in this sequence)

1. **B0** Character folders + `CharacterType` + `CharacterManager` dispatch + `ShopkeeperBehavior` + migration.
2. **B1** `move/` — NavMesh + A\* + MovementController + MovePackets; a barker that patrols waypoints.
3. **B2** `region/` — ARM/WG/radius sources; inside/near queries.
4. **B3** `barker/` — FSM, ProspectTracker, invite dialog, cooldowns + persistence.
5. **B4** commands, config, ARM-owner polish; compile-clean; deploy to umbralcraft for the one big test.

## 8. Risks & build-time verifications

- **1.21.11 move packet** — confirm relative-move vs `EntityPositionSync` via `javap` (like P1 spawn).
- **A\* cost** — hard caps + off-thread search + path cache; never search on the main thread.
- **ARM API surface** — compile against the local jar; guard every call; verify the owner/bounds accessors
  exist in 3.5.5.
- **Moving-NPC tracking** — reuse P1 viewer scan keyed on live position; watch for spawn/despawn thrash at
  range edges while walking (hysteresis if needed).
- **Voice on hot-load** — unchanged caveat: SVC API only at real restart; umbralcraft full-restart to test.
