# LivingShopkeeper — design spine

Voiced, memory-keeping NPC merchants. Server owners author packet-NPC shopkeepers,
their shops, and a large branching dialog bank entirely in YAML. Each shopkeeper is
drawn to nearby players over packets (no real entity), tracks the nearest listener,
and **speaks** through Simple Voice Chat at its position. Trade and talk are one loop:
buying, selling, haggling, greetings, idle barks and lore are all dialog triggers, and
per-player memory + reputation + mood make the same shopkeeper feel alive and different
each visit.

This document is the architecture spine. The rationale lives in
[ADR 0029](../../ai-continue/decisions/0029-livingshopkeeper-voiced-npc-merchant.md).

## Locked decisions (2026-07-12)

1. **Voice = offline Piper bank.** Dialog text is authored in YAML; an offline
   `stuffmaker` tool (Piper TTS) renders each line to a compact `.ogg` shipped with the
   plugin. The game server **only decodes + plays** — it never synthesizes and never
   calls the network. Dynamic values (price, player name) appear in the **subtitle**,
   not the voiced audio. A `voice.runtime-tts` toggle is left as a future opt-in that
   adds a second `VoiceSource` without changing anything else.
2. **Body = packet NPC.** Player-skinned NPCs drawn to nearby viewers over packets
   (the ZNPCsPlus / MachineConstruct pattern), not real entities. We own render,
   viewer-tracking, look-at and interaction; persistence is just YAML re-rendered on
   chunk-load / join.
3. **Two-way voice is out of scope for v1.** The shopkeeper speaks; players reply via
   GUI/chat choices. Mic-in → offline **Vosk** STT → intent → dialog branch is P9.

## Why this is mostly assembly, not invention

Three in-repo assets carry the load:

| Need | Reuse |
|---|---|
| Positional voice | [`videowall/.../audio/VoiceChatAudio.java`](../videowall/source/src/main/java/dev/servereer/videowall/audio/VoiceChatAudio.java) — `start(world,x,y,z,distance,supplier)` → locational SVC channel of 960-sample / 20 ms / 48 kHz mono frames + stop handle. |
| Voice file → PCM | [`machineconstruct/.../audio/TrackIngest.java`](../machineconstruct/source/engine/src/main/java/dev/servereer/machineconstruct/audio/TrackIngest.java) `decodeToWav()` (ffmpeg, off-thread) + [`AudioTrack.java`](../machineconstruct/source/engine/src/main/java/dev/servereer/machineconstruct/audio/AudioTrack.java) `load()/frameAt()`. |
| Packet NPC + tracking | MachineConstruct's packet-display layer + "viewer seen-set diffing" (references ZNPCsPlus). |
| Player skins offline | PixelProfiler's offline-safe skin cache pattern. |
| Offline content pipeline | `stuffmaker` generators (ADR 0027). |

## Runtime module map

```
dev.servereer.livingshopkeeper
├── LivingShopkeeperPlugin      # bootstrap, soft-depend guards (SVC/PE/Vault/PAPI), reload   [P0]
├── command/ LskCommand         # /lsk create|edit|reload|voices|rep|tp|version               [P0+]
├── npc/
│   ├── ShopkeeperManager       # load defs, spawn/despawn bodies, per-player proximity        [P1]
│   ├── PacketNpcRenderer        # spawn/despawn player-NPC to viewers; tab-list hygiene       [P1]
│   ├── SkinCache               # name/texture -> value+signature, cached to disk, offline     [P1]
│   ├── NpcInteractListener     # PacketEvents Interact packet -> shopkeeper -> open/advance    [P1]
│   └── LookController          # head/body rotation toward nearest listener; nod/shake emotes [P1/P6]
├── audio/
│   ├── VoiceChatAudio          # PORT of VideoWall's locational channel + stop handle          [P2]
│   ├── VoiceBank               # clip-id -> cached PCM (decode once via ffmpeg), LRU           [P2]
│   └── SpeechSession           # per-NPC "is speaking" queue; feeds SVC supplier; no overlap   [P2]
├── dialog/
│   ├── DialogEngine            # trigger + context -> node -> weighted line (cooldown/conds)   [P3]
│   ├── DialogNode / DialogLine # parsed YAML model                                            [P3]
│   ├── TriggerContext          # player, npc, time, weather, held item, rep, mood, history    [P3]
│   └── Subtitle                # action-bar / chat-choices / hologram-bubble renderer          [P3]
├── shop/
│   ├── ShopMenu                # custom buy/sell GUI (house style, not vanilla trades)         [P4]
│   ├── ShopStock               # stock limits, per-player limits, restock timers              [P4]
│   ├── PriceService            # base price; optional PixelProfiler prices.yml hook           [P4/P8]
│   └── Transaction             # Vault debit/credit -> fires buy/sell/fail dialog triggers    [P4]
├── social/
│   ├── RelationshipStore       # per player x npc: meetings, rep, last-seen, purchases, flags [P5]
│   └── MoodModel               # patience/warmth drift -> gates dialog + small price nudges    [P6]
└── store/                      # pluggable StorageProvider (sqlite | json), async            [P5]
```

## Living Dialog Mode update

The next conversation layer is specified in [DIALOG_MODE.md](DIALOG_MODE.md): double-left-click entry,
typed-chat and clickable replies, multiplayer rooms with one serialized NPC voice/body, compiled intent
indexes for very large banks, clip-paced gestures, short-term context and persistent memory. It extends the
trigger engine; right-click remains the quick trade/menu path.

## The dialog bank ("tons of dialog")

Authored like MachineConstruct machines — pure YAML, hot-reloadable.

**Triggers:** `approach`, `open_shop`, `close_shop`, `buy_success`, `buy_fail_poor`,
`sell`, `haggle_open`, `haggle_accept`, `haggle_reject`, `idle_bark`, `farewell`,
`first_meeting`, `returning`, `low_stock`.
**Conditions (`when`):** time of day, weather, `holds:<item>`, `rep >= N`,
`mood:<tag>`, `purchases >= N`, `met_before`, custom flags, per-node `cooldown`.

```yaml
# dialog/blacksmith.yml
node greet_returning:
  triggers: [approach]
  when: { met_before: true, rep: ">=10", cooldown: 45s }
  lines:
    - text: "Back again, <player>? That pickaxe treating you right?"
      voice: bs_greet_return_01      # -> voice/blacksmith/bs_greet_return_01.ogg
      weight: 3
    - text: "Ah, my best customer. Coin's warm in your pocket, I hope."
      voice: bs_greet_return_02
      mood: warm
  choices:
    - { text: "Show me your wares.", goto: open_shop }
    - { text: "Any rumors?",         goto: node:gossip_pool }
  actions: [ look_at_player ]

node buy_fail_poor:
  triggers: [buy_fail_poor]
  lines:
    - { text: "Come back when your purse is heavier.", voice: bs_broke_01, mood: dry }
```

- `<player>`, `<price>`, `<item>` substitute into the **subtitle text**; the audio is
  the fixed bark for that node. (Number/word splicing to voice prices is a later option.)
- Line selection is **weighted, cooldown-gated, condition-filtered** so repeats vary.
- Subtitle modes (config): action bar · chat with clickable choices · floating
  hologram speech-bubble over the NPC.

## Voice pipeline

**Author time (offline, `stuffmaker/shopkeepervoicegen`, P7):** read the dialog YAML →
Piper per `voice:` clip → normalize → encode `.ogg` (48 kHz mono opus) →
`voice/<profile>/<clip>.ogg`. Hash `text + voice params` so re-runs only regenerate
changed lines. **Piper and the voice model live in the generator only — never on the
game server.**

**Runtime:** ship only the `.ogg` bank. `VoiceBank` decodes each clip once (bundled
ffmpeg) → caches PCM → `SpeechSession` feeds SVC's supplier one 20 ms frame at a time.
Decoding never happens on the audio thread. (Zero-binary option for later: generator
emits a pre-baked PCM pack, or shade `concentus` for pure-Java Opus.)

## What makes it "living"

- **Look-at** the nearest listener; nod/shake emotes on accept/reject.
- **Memory:** first-meeting vs returning; references last purchase, gear, time-since-visit.
- **Reputation ladder:** repeat trade → discounts, special stock, unique lines, gifts.
- **Mood:** patience/warmth drift with behavior (lowball haggles annoy; loyalty warms)
  → nudges price a few % and gates dialog pools; shown via particles + villager sounds.
- **Haggling:** propose a price (GUI/chat) → accept / counter / refuse, voiced + mood-gated.
- **Ambient life:** idle barks on a timer; optional day/night schedule + small wander leash.
- **Gossip:** shopkeepers reference each other.

## Data / file layout

```
plugins/LivingShopkeeper/
├── config.yml                  # subtitle mode, hearing distance, storage, voice toggles
├── shopkeepers/<id>.yml        # identity, skin, world+location, dialog pack, shop, voice profile
├── dialog/<pack>.yml           # dialog nodes
├── shops/<id>.yml              # stock, prices, limits (or inline in the shopkeeper)
├── voices.yml                  # profile -> piper model + pitch/speed (drives the generator)
├── voice/<profile>/*.ogg       # generated voice bank (shipped)
├── bin/ffmpeg                  # decode (reused pattern; optional path override)
└── data/                       # sqlite/json relationship + mood store
```

## Threading & performance

- SVC pulls frames on its own cadence → PCM is **pre-decoded and cached**; the supplier
  just hands out `frameAt()` slices. Never decode on the audio thread.
- Proximity + idle-bark scan on one cheap repeating task; dialog selection is light.
- Storage writes async, flushed like MachineConstruct's stores.
- Packet NPCs render to viewers-in-range only; look-at rotation is throttled.

## Phase plan

P0 scaffold (loads clean) → **P1 packet-NPC + skin + interaction + look-at** →
P2 voice playback → P3 dialog engine → P4 shop + Vault → P5 memory/reputation →
P6 mood + haggling + emotes → P7 offline voice generator → P8 schedules/gossip/pricing →
**P9 two-way Vosk STT (stretch)**.

## Risks / notes

- **Voice needs SVC** — no-SVC = silent (still fully functional). Document loudly.
- **Hot-load caveat:** SVC hands out its server API once at startup, so a PlugMan
  hot-load leaves NPCs mute until a real restart (same caveat as VideoWall / ArcadeCab).
- **Runtime decode** uses the bundled ffmpeg you already ship; the zero-binary route is
  available later if we want LivingShopkeeper fully self-contained.
- **Offline voice** means dynamic values are subtitle-only in v1 — accepted.
