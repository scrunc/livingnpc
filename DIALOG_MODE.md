# LivingShopkeeper — Living Dialog Mode

Status: **implementation plan** · 2026-07-16 · source not implemented yet

Dialog Mode turns a packet NPC from a clickable line dispenser into a character players can hold a
conversation with. The entry gesture is deliberate: **left-click the same NPC twice**. The NPC stops what it
is doing, acknowledges the player, faces them naturally, and begins a turn-based conversation. Players can
type normally or use optional clickable suggestions; no inventory GUI interrupts the scene.

This document specifies player experience, multiplayer behavior, authoring, performance, failure handling,
and delivery order. The associated decision record is
[ADR 0031](../../ai-continue/decisions/0031-livingshopkeeper-living-dialog-mode.md).

---

## 1. Experience target

A good conversation should look like this:

1. A player double-left-clicks Cassie.
2. Cassie finishes the step she is taking, turns toward the player, and gives a small acknowledgement.
3. `Talking to Cassie — type naturally, click a suggestion, or /lsk leave` appears unobtrusively.
4. Cassie greets the player using the current place, time, weather, relationship, and recent conversation.
5. The player types `what is happening at the club tonight?`.
6. Cassie listens rather than instantly replying: a short reaction, then a contextual voiced answer.
7. A second player joins. Cassie glances toward them, welcomes them without restarting the first player's
   conversation, and alternates attention as each person speaks.
8. Cassie does not repeat a line heard moments ago, interrupt herself, patrol away mid-sentence, or stare at
   one exact eye position like a turret.
9. Saying `bye`, sneaking and double-clicking, walking away, or timing out ends the conversation with an
   optional farewell. Cassie resumes the activity she paused.

The system is authored and deterministic. It does **not** require a cloud LLM or runtime TTS. A later speech-
to-text input can feed the same intent resolver without changing the conversation engine.

---

## 2. Entering and leaving

### 2.1 Double-left-click

Packet NPCs receive `INTERACT_ENTITY / ATTACK` for a left click. `NpcInteractListener` will route that action
to a `DoubleClickTracker` keyed by `(player UUID, NPC UUID)`.

- Maximum interval: configurable, default **550 ms**.
- Minimum interval: default **80 ms**, rejecting duplicate/noisy packets.
- Maximum start distance: default **6 blocks**, same world, NPC visible to the player.
- Clicking another NPC replaces the pending first click instead of completing it.
- The first click only gets the NPC's attention; the second starts or joins Dialog Mode.
- A player can hold one active NPC conversation at a time.
- Double-left-clicking while already participating refocuses the conversation; it does not accidentally exit.
- Sneak + double-left-click exits. `/lsk leave`, `bye`/`goodbye`, distance, teleport, quit, and timeout also exit.

Right-click keeps its current quick action: trade/menu/one-off interaction. This preserves fast access for
players who do not want a conversation.

### 2.2 Chat capture

While engaged, ordinary player chat becomes their turn to the NPC. The mode indicator makes that scope clear.

- Prefix `!` sends a public chat message without leaving Dialog Mode.
- Commands remain commands.
- Input is capped (default 160 characters), normalized, and rate-limited.
- Optional clickable suggestions appear in chat; they call the same intent path as typed text.
- The old inventory `DialogMenuGui` remains available as a compatibility renderer, not the primary mode.

---

## 3. Conversation ownership and multiplayer

The central rule is: **one physical NPC has one mouth and one body**.

### 3.1 Conversation room

Each NPC may own one `ConversationRoom`:

```text
ConversationRoom (per NPC)
├── participants: ordered player set
├── observers: nearby players who can hear but cannot enqueue turns
├── focus: player the NPC is currently addressing
├── shared topic + place context
├── fair turn queue
├── active NPC utterance / voice handle
└── room state and timeout

ParticipantState (per player inside the room)
├── current node / expected intents
├── short-term slots and facts
├── recent input + reply history
├── relationship-memory snapshot
└── last activity / distance / generation token
```

The room owns voice, body pose, gaze, and turn ordering. Participant state owns private branching and memory.
This prevents two players from making the same NPC speak two clips over itself while still allowing personal
responses such as reputation, purchase history, and last visit.

### 3.2 Turn fairness

- One NPC utterance plays at a time.
- Player turns received while the NPC is speaking enter a bounded queue.
- Round-robin fairness prevents one fast typist from starving everyone else.
- Default limits: 3 pending turns/player, 16 total, 6 active participants.
- Repeated or near-identical messages from one player are coalesced.
- When the focus changes, the NPC turns gradually toward the next speaker and may glance at other members.
- The speaker receives reply suggestions; all room participants receive the NPC subtitle.
- Voice stays locational, so nearby observers naturally hear it.
- If the room is full, a player joins as an observer and gets a clear busy message.

### 3.3 Group awareness

Authoring gains conditions/placeholders:

```text
<speaker>       current player being answered
<other_player>  another participant, if one exists
<group_size>    active participant count
<topic>         current room topic
when.group-min / when.group-max
when.joining / when.interrupting / when.returning
```

Joining does not restart the root greeting. It fires a `conversation_join` branch. Leaving fires
`conversation_leave`; the final participant leaving ends the room.

---

## 4. State machine and behavior priority

```text
IDLE
  └─ double-left-click ─> OPENING
OPENING
  ├─ validate range/availability
  └─ attention cue + stop movement ─> NPC_SPEAKING or PLAYER_TURN
NPC_SPEAKING
  ├─ queue player input without interrupting audio
  └─ clip/subtitle complete ─> PLAYER_TURN
PLAYER_TURN
  ├─ input ─> RESOLVING ─> NPC_SPEAKING
  ├─ another player joins ─> JOINING ─> NPC_SPEAKING
  └─ timeout/distance/bye ─> CLOSING
CLOSING
  └─ optional farewell complete ─> IDLE + resume activity
```

NPC behavior priority becomes:

```text
disable/reload cleanup
  > active conversation
  > scheduled place visit/sitting
  > barker invitation
  > patrol
  > idle life
```

A scheduled visit becoming due during a conversation waits. The NPC finishes or politely closes the room,
then travels. Sitting NPCs remain seated while talking unless an authored delivery explicitly says to stand.

---

## 5. Natural delivery

Random movement alone does not create life; animation must follow conversational meaning.

Each NPC line may declare a delivery profile:

```yaml
say:
  - text: "The club wakes up after sunset."
    voice: club_hours_1
    mood: warm
    delivery: explain
    gesture: [small_nod, glance_away]
    pause-before: 350ms
    pause-after: 500ms
```

Delivery profiles control bounded, non-repeating motion:

- `greet`: open stance, brief wave, eye contact.
- `listen`: still body, occasional head adjustment, rare short nod.
- `explain`: one or two gestures paced across the clip.
- `excited`: quicker turn, wider gesture, never constant arm spam.
- `unsure`: glance away, hesitation, softer fallback.
- `annoyed`: reduced eye contact, shorter reply, mood change.
- `farewell`: wave/turn-away, then resume route.

Audio duration is authoritative. Gestures are scheduled at percentages of the clip or explicit cue times, not
at a fixed repeating interval. A silent line uses an estimated reading duration. During a player's turn the NPC
enters a listening pose and does not patrol, jump, or perfectly mirror every degree of player head movement.

The room owns animation so two participants cannot fight over body rotation.

---

## 6. Large dialog authoring model

Dialog can be split into any number of YAML files under a character folder:

```text
characters/cassie/
├── character.yml
├── dialog/
│   ├── conversation.yml
│   ├── topics/
│   │   ├── shop.yml
│   │   ├── club.yml
│   │   ├── town.yml
│   │   └── rumors.yml
│   ├── places/
│   │   ├── market.yml
│   │   └── docks.yml
│   └── fallbacks.yml
└── voice/
```

Example conversation graph:

```yaml
conversation:
  start: hello
  timeout: 45s

nodes:
  hello:
    say:
      - {text: "You wanted to talk?", voice: talk_hello_1, delivery: greet}
      - {text: "Go on, I'm listening.", voice: talk_hello_2, delivery: listen}
    replies:
      - intent: shop
        examples: ["show me the shop", "what do you sell", "wares", "buy something"]
        goto: shop_intro
      - intent: club
        examples: ["tell me about the club", "party tonight", "where is the music"]
        goto: club_intro
      - intent: identity
        examples: ["who are you", "what is your name"]
        goto: about_self
    suggestions: ["What do you sell?", "What's happening at the club?", "Who are you?"]
    fallback: clarify

  club_intro:
    topic: club
    when: {place: club}
    say:
      - text: "You're already here. Music starts properly after dark."
        voice: club_here_1
        delivery: explain
    replies:
      - {intent: club_hours, examples: ["when", "what time", "how late"], goto: club_hours}
      - {intent: club_people, examples: ["who comes here", "is anyone here"], goto: club_people}
      - {intent: back, examples: ["something else", "back"], goto: hello}

  clarify:
    say:
      - {text: "Do you mean the shop, the club, or something around town?", voice: clarify_1, delivery: unsure}
    suggestions: ["The shop", "The club", "Around town"]
    return-to-previous: true
```

### 6.1 Intent rules

Resolution order is deterministic:

1. Exact normalized example/alias.
2. Current-node intent candidates.
3. Current-topic intent candidates.
4. Global intents such as greeting, goodbye, repeat, help, trade.
5. Bounded fuzzy comparison over the best indexed candidates.
6. Contextual clarification fallback.

Intent rules may include required/forbidden tokens, regex for tightly controlled cases, conditions, slot
capture, priority, and target node. Free text never becomes a command and is never evaluated as YAML/code.

### 6.2 Conversation memory

Three scopes keep behavior understandable:

- **Turn:** extracted slots and the message currently being answered.
- **Session:** current topic, previous nodes, last 12 lines, unanswered question, participants.
- **Persistent player × NPC:** meetings, last topic/time, relationship, authored flags, purchases and promises.

Recent-node and recent-line rings suppress repetition before weighted selection. Persistent writes are async
and batched; a conversation turn never blocks on SQLite.

---

## 7. Optimized runtime architecture

### 7.1 Components

```text
conversation/
├── ConversationService       # player↔room ownership, lifecycle, commands
├── ConversationRoom          # one mouth/body/queue per NPC
├── ParticipantState          # private cursor, slots, history
├── DoubleClickTracker        # bounded per-player/NPC entry detector
├── ChatTurnListener          # captures dialog-scoped chat safely
├── TurnScheduler             # fairness, backpressure, timeouts
├── ConversationCompiler      # YAML -> validated immutable graph
├── ConversationGraph         # compact node arrays + id indexes
├── IntentIndex               # exact aliases + token inverted index
├── IntentResolver            # bounded matching and fallbacks
├── DeliveryController        # voice/subtitle/gesture timeline
├── ConversationMemory        # cache + async persistence adapter
└── ConversationDiagnostics   # stats, trace, validation reports
```

### 7.2 Compile once, select cheaply

Raw YAML is never searched on a player turn. `/lsk reload` compiles it into an immutable graph:

- Node ids become integer ordinals with an O(1) id map.
- Edges and fallbacks resolve and validate at compile time.
- Exact aliases use a hash map.
- Normalized tokens use an inverted index (`token -> candidate rule ids`).
- Candidate intersections are bounded before scoring.
- Weighted line pools precompute cumulative weights/alias tables.
- Conditions become immutable specs evaluated only for candidates.
- Missing nodes, unreachable nodes, empty pools, duplicate aliases and fallback cycles produce diagnostics.

Target scale per server:

```text
200 characters
10,000 nodes / 50,000 lines in a large character bank
100 online players
dozens of simultaneous rooms
```

Turn matching should be `O(input tokens + bounded candidates)`, not `O(all lines)`.

### 7.3 Thread and reload model

- Packet callbacks only extract safe primitive data, then schedule Bukkit work on the main thread.
- Chat normalization and immutable intent scoring may run async.
- A session generation token discards stale async results after leave/reload/teleport.
- Bukkit world, player, inventory, permission and PlaceholderAPI reads remain on the main thread.
- Dialog compilation happens off-thread, then swaps one immutable library reference atomically.
- Existing rooms pin their graph version until the current turn completes, then migrate by node id or close
  gracefully if the node disappeared.

### 7.4 Voice memory

The current eager decoded voice bank will not scale to tens of thousands of clips. Dialog Mode should add:

- Lightweight clip metadata index at load (path, format, duration).
- Size-bounded decoded PCM LRU cache.
- Async prefetch of likely next-node clips while waiting for the player's turn.
- Active clips pinned until playback completes.
- Subtitle-only fallback on a decode miss/failure; never stall the server tick.

### 7.5 Hard bounds

Defaults:

```yaml
dialog-mode:
  enabled: true
  double-click-ms: 550
  double-click-min-ms: 80
  start-distance: 6
  leave-distance: 9
  idle-timeout-seconds: 45
  room-timeout-seconds: 90
  max-participants: 6
  max-room-queue: 16
  max-player-queue: 3
  max-input-characters: 160
  intent-max-candidates: 64
  fuzzy-max-candidates: 16
  recent-lines: 12
  decoded-voice-cache-mb: 256
```

All queues, histories, click trackers, caches, and sessions have TTL/size bounds. There is no per-tick scan of
the full dialog bank or every offline player.

---

## 8. Diagnostics and safety

Admin tools planned:

```text
/lsk dialog validate [character]
/lsk dialog stats [character]
/lsk dialog trace <character> <player>
/lsk dialog rooms
/lsk dialog end <character|player>
/lsk reload
```

`trace` explains normalization, candidate intents, rejected conditions, chosen node/line, cooldown and repeat
suppression. It must redact private chat by default in persistent logs; live admin tracing is permission-gated.

Failure behavior:

- Missing voice: subtitle + delivery timing fallback.
- Missing target node after reload: apology/farewell, then close.
- Full queue: NPC acknowledgement and a short “one moment” indicator; do not silently drop turns.
- Player leaves range/world/quits: remove immediately and advance the room queue.
- NPC removal/reload/disable: close rooms, restore chat, stop voice, clear poses and movement locks.
- No matching intent: clarify with relevant suggestions, never loop one generic line indefinitely.

---

## 9. Implementation roadmap

### D0 — Schema and compiler

- New conversation YAML schema, immutable graph, validation report.
- Exact/token intent indexes, repeat suppression, fixture bank.
- Unit tests for graph links, conditions, cycles, matching and large-bank load.

### D1 — Entry and single-player session

- Handle `ATTACK`, double-click tracker, distance/debounce checks.
- `ConversationService`, one active session/player, chat capture, `/lsk leave`.
- Pause/resume NPC movement and retain right-click behavior.

### D2 — Turn delivery

- NPC-speaking/player-turn states, voice completion callback, subtitle routing.
- Clip-paced gestures, listening pose, suggestions, goodbye/timeout.
- No-repeat line/node histories.

### D3 — Natural-language intent resolution

- Exact, node, topic, global and bounded fuzzy matching.
- Clarification ladder, slots, repeat/help/back/global intents.
- Debug trace explaining every choice.

### D4 — Multiplayer rooms

- Join/leave branches, focus and gaze, fair bounded queue, observer behavior.
- Per-player cursors plus shared room topic.
- Spam coalescing and simultaneous-input tests.

### D5 — Memory

- Session facts and persistent player × NPC memory.
- Async SQLite WAL writer, batched updates, cache/TTL.
- Returning-player and relationship conditions/placeholders.

### D6 — Scale pass

- Async compile + atomic graph swap.
- Voice metadata index, decoded PCM LRU and prefetch.
- Metrics, soak tests and allocation profiling.

### D7 — Content and live test

- Convert Cassie into a deep example bank covering shop, club, town, rumors and place-specific talk.
- Record/generate missing voice clips.
- Live test: solo, two-player interruption, six-player room, reload mid-turn, disconnect, range leave,
  scheduled-place transition, missing clip and 50k-line synthetic bank.

No deployment should happen before D0–D4 pass automated tests and the packet metadata/animation paths are
smoke-tested on the target 1.21.11 client.

---

## 10. Definition of done

Dialog Mode is complete when:

- Double-left-click reliably enters without conflicting with right-click/trade.
- Typed input and suggestions follow the same authored graph.
- The NPC never overlaps its own voice or lets players starve one another.
- Multiple participants are acknowledged as one physical group conversation.
- Movement, gaze and gestures follow turn state and clip timing.
- Recent dialogue does not visibly repeat.
- Place, time and persistent player context can change replies.
- Leaving restores chat and the NPC's prior schedule/patrol safely.
- Reload/removal/disable cannot strand sessions or client-side entities.
- A synthetic 50k-line bank stays within configured CPU/memory/queue bounds.
- Authors can validate and trace a conversation without reading server code.
