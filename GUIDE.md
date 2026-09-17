# LivingShopkeeper: complete usage guide

LivingShopkeeper creates client-side, packet-rendered NPC characters for Paper servers. A character can
stand at a post, look at nearby players, speak through Simple Voice Chat, show subtitles, open a dialog
menu, run click actions, sell items, patrol, sit, react to attacks, or act as a player-owned shopkeeper.

This is the operational guide for the current source in this repository. `DESIGN.md` explains the internal
architecture and phase history; `DIALOG_MODE.md` describes a future conversation mode and is not a promise
that every feature in it exists today.

## 1. What the plugin does

The plugin uses a packet NPC rather than a real Minecraft entity. The NPC is therefore visible to nearby
viewers without creating a normal mob, taking damage as an entity, or adding a server-side collision body.
The plugin owns the NPC's appearance, position, look direction, name/tag, interaction packets, movement,
and per-viewer visibility.

The main uses are:

- `shopkeeper`: a dialog-first NPC. Right-click can speak, open a dialog GUI, or run configured actions.
- `shop`: a trading NPC. An admin shop has infinite stock; a player shop is backed by a linked chest.
- `lobby`: a hub NPC that wanders around a post, sits at marked seats, returns to the post, and runs clicks.
- `barker`: a shop crier that patrols waypoints, notices players, invites them, and uses place-specific talk.
- `informer`: accepted by the command parser, but it is not a separate implemented behavior yet; do not use
  it for production content.

Voice, dialog, and most visual content are authored as files. The game server does not synthesize speech at
runtime. WAV and MP3 clips are decoded asynchronously and cached; Simple Voice Chat is optional, so the NPC
and subtitles still work when voice is unavailable.

## 2. Requirements and installation

The plugin is built for Paper 1.21.x and uses Java 21 in this repository. Install these plugins as needed:

| Dependency | Required? | Why |
|---|---:|---|
| PacketEvents | Yes | Renders and interacts with packet NPCs. Without it, characters cannot be created or rendered. |
| LivingShopkeeperVoice | Recommended | Audio-core companion that keeps the voice channel available across plugin reloads. |
| Simple Voice Chat | Optional | Lets players hear positional NPC speech. Without it, subtitles and gameplay still work. |
| Vault + economy provider | Optional | Needed when a future/connected economy integration is used; item-backed shops do not require it. |
| PlaceholderAPI | Optional | Provides voice-status placeholders for scoreboards, badges, or other plugins. |
| WorldGuard/ARM | Optional | Restricts player shop placement to owned/member regions when installed. |

Install the main jar and, if voice is wanted, the `LivingShopkeeperVoice` jar. Start the server once so
`plugins/LivingShopkeeper/` is created. The first setup should be done with a real restart, especially when
Simple Voice Chat is present. `/lsk reload` reloads config, characters, dialog, and changed voice clips, but a
full restart remains the safest way to initialize the voice API after installing the plugin.

Required permissions:

```text
livingshopkeeper.admin       manage characters, files, shops, tags and actions (default: op)
livingshopkeeper.use         interact with characters (default: true)
livingshopkeeper.shop.create place a personal shop (default: true)
```

## 3. Runtime file layout

The current runtime layout is per character:

```text
plugins/LivingShopkeeper/
├── config.yml
├── characters/
│   └── blacksmith/
│       ├── character.yml
│       ├── dialog/
│       │   ├── default.yml
│       │   └── rumors.yml
│       └── voice/
│           ├── greet_1.wav
│           └── greet_2.mp3
└── data/                  # relationship/storage data when enabled by the build
```

Character IDs are folder names and should contain only letters, numbers, `_`, and `-`. Dialog files in a
character's `dialog/` directory are merged into that character's pack. The filename is not the pack name in
the current implementation: all YAML files in that directory are loaded together.

The generated `character.yml` is the source of truth for identity, location, skin, actions, tags, shop data,
seats, and barker data. Prefer the commands and GUIs for edits that have a command/GUI; edit YAML directly for
dialog and advanced settings, then run `/lsk reload`.

Legacy `shopkeepers/<id>.yml` files may be migrated by the character manager. New work should use
`characters/<id>/character.yml`.

## 4. The command reference

All commands below require `livingshopkeeper.admin` unless stated otherwise.

### Character lifecycle

```text
/lsk                         show help
/lsk create <id> [type] [skin]
/lsk list
/lsk tp <id>
/lsk remove <id>
/lsk version
/lsk reload
```

`type` can be `shopkeeper`, `shop`, `lobby`, `barker`, or `informer`. If omitted, it is `shopkeeper`.
The location is the executing player's current location, so these creation commands must be run in-game.
`skin` is a player name unless the skin command is used later.

### Identity and appearance

```text
/lsk name <id> <MiniMessage>
/lsk name <id> reset
/lsk skin <id> <playerName>
/lsk skin <id> url <textures.minecraft.net URL or hash>
/lsk skin <id> value <base64> [signature]
```

The name is used in subtitles and as the default floating name. MiniMessage is accepted, for example
`<gold><b>Bruno the Blacksmith`. A raw texture value can be too long for a Minecraft command; use a player
name or textures URL when possible.

### Floating tags

```text
/lsk tag <id> add <MiniMessage line>
/lsk tag <id> set <line-number> <MiniMessage line>
/lsk tag <id> remove <line-number>
/lsk tag <id> list
/lsk tag <id> clear
```

Tags are multi-line floating text and replace the plain name display while present. Each line is MiniMessage.

### Click actions

```text
/lsk action <id> add player:/warps
/lsk action <id> add console:give %player% emerald 1
/lsk action <id> add message:<gold>Welcome, <player>!
/lsk action <id> add sound:entity.experience_orb.pickup
/lsk action <id> list
/lsk action <id> remove <line-number>
/lsk action <id> clear
```

If a character has actions, clicking it runs those actions instead of falling through to the dialog/shop
behavior. `player:` runs as the player, `console:` as console, `message:` sends a MiniMessage message, and
`sound:` plays a Bukkit sound. `%player%` is supported in console commands; use the actual project behavior
and test any other placeholders before publishing them.

## 5. Character types in detail

### 5.1 Standard shopkeeper

Use a standard shopkeeper for a stationary greeter, quest marker, lore NPC, or menu-driven conversation.
It can have dialog lines, conditions, voice, actions, tags, and hit reactions. It does not automatically
create item trades; use the `shop` type for trading.

On approach or interaction, the character can trigger dialog. Right-click opens a dialog menu when the pack
has a suitable node with `options:`; otherwise it uses the normal interaction path and a matching `interact`
line, fallback greeting, action, or shop behavior.

### 5.2 Admin shop

Create an admin shop with:

```text
/lsk create minerals shop Notch
```

An admin shop has no owner and no linked stock chest, so its configured trades are treated as infinite stock.
Right-clicking opens the owner/admin management GUI. Add trades with the click-driven builder. The builder
supports up to two cost items and one result item, copies templates from your inventory, and never moves the
real template into the GUI.

### 5.3 Player-owned shop

Player shops are created with a tagged item, not an ordinary spawn egg:

```text
/lsk shopitem Alice 1
```

The player must hold the item and:

1. Right-click their own chest. This links the chest as stock and payment storage.
2. Right-click the block where the NPC should stand.
3. Keep the standing point within `shop.max-container-distance` blocks of the chest.
4. Click the new NPC to open the owner GUI and add trades.

When WorldGuard/ARM is installed and `shop.require-region` is true, placement is restricted to the player's
region. With the default `shop.require-region-owner: true`, the player must own the region, not merely be a
trusted member. The owner skin and name are applied to the NPC. The chest remains a normal chest: sold result
items are taken from it and payments go into it.

The owner can manage trades, rename the NPC, change its skin, move the NPC, move the linked chest, configure
seats, open the customer view, or delete the shop. Deleting the NPC does not delete chest contents.

### 5.4 Lobby NPC

Lobby NPCs provide a little movement and a reliable clickable hub post:

```text
/lsk create ranks lobby Notch
/lsk lobby ranks post
/lsk lobby ranks seatwand
```

With the seat wand, right-click slabs or stairs to mark seats; sneak-right-click removes a seat. The NPC
wanders within `lobby.roam-radius`, may sit, and runs back to its post when someone approaches. The default
lobby dialog uses `greet`. Add actions such as `player:/ranks` to turn it into a server navigation NPC.

### 5.5 Barker

A barker is a moving shop crier. Create it, set a home, then add waypoints:

```text
/lsk create crier barker Cassie
/lsk barker crier home
/lsk barker crier addwaypoint
/lsk barker crier addwaypoint
/lsk barker crier region market
/lsk barker crier cooldown 12
```

Repeat `addwaypoint` from each desired location. It uses bounded navigation, patrol pauses, notice/invite
radii, and the `invite` dialog trigger. Its effective re-greeting delay is the larger of the command/config
cooldown and the dialog node cooldown. A player who enters the shop region can receive `invite_success`; a
player who ignores an invitation can receive `invite_giveup`.

Barker activity places add contextual destinations:

```text
/lsk barker crier area market
/lsk barker crier visit tavern 18:00 02:00
/lsk barker crier sit bench 22:00 01:00
/lsk barker crier places
/lsk barker crier removeplace bench
```

`area` selects conversation while the NPC passes through. `visit` sends it there during the time window.
`sit` does the same and mounts it on a client-side seat. Windows can wrap midnight. Author `arrive_<name>`,
`ambient_<name>`, `approach_<name>`, and `interact_<name>` triggers for a place.

## 6. Dialog authoring

Dialog is YAML under `characters/<id>/dialog/`. A file contains `nodes`; each node has one or more triggers
and either `lines`, `options`, or both.

### 6.1 A simple line node

```yaml
nodes:
  welcome:
    triggers: [approach, interact]
    cooldown: 12s
    lines:
      - "Welcome, <player>!"
      - { text: "Good <time> to you.", voice: greeting_2, weight: 2 }
```

Bare strings show subtitles only. A map can contain `text`, `voice`, `weight`, `mood`, and `when`. Omit
`text` for voice-only lines. Omit `voice` for subtitle-only lines.

### 6.2 How selection works

When a trigger fires, the engine:

1. Loads the character's merged dialog pack.
2. Rejects nodes whose `when` conditions fail.
3. Rejects nodes on that player/NPC node's cooldown.
4. Weight-picks one eligible node.
5. Rejects lines whose own `when` conditions fail.
6. Weight-picks one eligible line.
7. Renders the subtitle and starts the selected voice clip.

There is also a global per-player/per-NPC gap of about 1.2 seconds to prevent double-firing. A node's default
cooldown is 5 seconds. `cooldown` accepts `500ms`, `8s`, `2m`, `1h`, or a bare number interpreted as seconds.

### 6.3 Triggers currently used by the implementation

Common triggers are `greet`, `interact`, `approach`, `thanks`, `farewell`, `gossip`, `invite`,
`invite_success`, `invite_giveup`, `arrive_<place>`, `ambient_<place>`, `approach_<place>`,
`interact_<place>`, `hit_punch`, `hit_again`, `hit_weapon`, `hit`, `flee`, `fightback`, `fight_hit`, and
`calm`. Shop behavior owns the trade flow and ships `greet`, `interact`, `thanks`, `farewell`, and `gossip`
examples. Use the exact trigger emitted by the behavior you are authoring.

### 6.4 Conditions

`when` conditions combine as AND conditions. Supported keys are:

| Key | Values/example | Meaning |
|---|---|---|
| `time` | `day`, `dusk`, `night`, `dawn` | Coarse Minecraft world-time bucket. |
| `weather` | `clear`, `rain`, `thunder` | Current weather. |
| `holds` | `DIAMOND_SWORD` | Player's main-hand material. |
| `chance` | `0.25` | 25% chance for the node/line to be eligible. |
| `permission` | `server.vip` | Player must have the permission. |
| `voicechat` | `connected`, `installed`, `none` | Filters by Simple Voice Chat state. |

Memory/reputation conditions described in older design documents are not part of the current condition parser;
do not rely on `rep`, `met_before`, or `purchases` until implemented in the runtime you deploy.

### 6.5 Placeholders and subtitles

Supported dialog text placeholders are:

```text
<player>   the viewer's name
<npc>      the character display/name
<time>     morning, day, evening, or night wording
```

Audio is prerecorded, so placeholders are substituted in the subtitle only. Record voice lines that sound
natural without a dynamic name or price. `subtitle-mode: chat` is the default. `action_bar` shows the line
above the hotbar. `bubble` currently falls back to chat in the dialog engine.

### 6.6 Dialog menus and branching replies

An `options` node is a click-to-talk GUI. Each option can have `text`, `icon`, `say` (voice clip), `line`
(subtitle), and `goto` (the next node ID). A node without `triggers` defaults to `interact`.

```yaml
nodes:
  start:
    title: "Ask the Cartographer"
    triggers: [interact]
    options:
      - { text: "Where is the market?", icon: COMPASS, line: "The market is east.", goto: market }
      - { text: "Tell me a rumor", icon: BOOK, line: "The old mine is active again.", goto: rumor }
      - { text: "Goodbye", icon: BARRIER }

  market:
    title: "The market"
    options:
      - { text: "Thanks", icon: EMERALD, line: "Safe travels!" }

  rumor:
    title: "A rumor"
    options:
      - { text: "Interesting...", icon: PAPER, line: "Keep it to yourself." }
```

The normal chat message is used for replies. Keep options short enough to fit their item names. A `goto`
target must be another menu node to continue; otherwise the GUI closes.

## 7. Voice setup

Put clips in `characters/<id>/voice/`. The filename without the extension is the clip ID:

```text
characters/bruno/voice/greeting_1.wav  -> voice: greeting_1
characters/bruno/voice/greeting_2.mp3  -> voice: greeting_2
```

WAV and MP3 are accepted. Mono, 48 kHz is a good target, but the loader handles ordinary rates/channels.
The plugin uses the voice-core/Simple Voice Chat path for positional audio. `voice.distance` controls the
hearing distance; `voice.enabled: false` disables playback while keeping subtitles.

The voice bank is decoded off-thread and cached. `/lsk reload` picks up new and changed clips additively and
does not cut a line already playing. If a clip is missing or cannot decode, the subtitle can still appear and
the server logs a warning. The old flat `voice/` layout in early notes is obsolete for new characters.

For generated voices, render audio outside the game server with the repository's `stuffmaker/shopkeepervoicegen`
tool or another TTS workflow. Keep generated filenames synchronized with `voice:` IDs. Do not put API keys in
the server's plugin directory or in dialog YAML.

## 8. Combat and natural behavior

The NPC can notice players, track a nearby viewer, glance, fidget, move through doors/gates/trapdoors (not
iron doors), sit, and look occupied. These behaviors are global in `config.yml` and can be adjusted carefully;
excessive glance/fidget or voice frequency makes a hub noisy.

When combat is enabled, a punch fires `hit_punch`, repeated punches fire `hit_again`, and a sword/axe/trident/
mace hit fires `hit_weapon`. After `combat.react-after-hits`, the configured reaction is `flee`, `fightback`,
or `none`. `fightback` is scripted behavior and damage, not normal PvP; it can land inside protected regions,
so use it only where that is acceptable. The no-invite cooldown prevents an attacked barker from immediately
inviting the attacker again.

Set a per-character override with:

```text
/lsk combat bruno none
/lsk combat crier flee
/lsk combat crier default
```

## 9. Configuration reference

The main keys are in `source/src/main/resources/config.yml` and are copied to the live plugin directory.
Important settings:

```yaml
subtitle-mode: chat             # chat | action_bar; bubble currently falls back to chat
hearing-distance: 16
storage: sqlite                 # sqlite | json, where supported by the deployed build

voice:
  enabled: true
  distance: 16
  runtime-tts: false            # keep false; runtime synthesis is not implemented

shop:
  creation-item: VILLAGER_SPAWN_EGG
  max-per-player: 2
  max-container-distance: 10
  require-region: true
  require-region-owner: true
  attend-radius: 8
  gossip-chance: 0.35

lobby:
  trigger-radius: 2.0
  roam-radius: 8
  sit-chance: 0.35
  sit-seconds: 25

combat:
  enabled: true
  reaction: flee
  react-after-hits: 3
```

Changes to config normally require `/lsk reload`. Test high-impact values on a staging server first. In
particular, lower `voice.distance`, increase dialog cooldowns, and disable combat in public hubs if those
systems would be distracting.

## 10. Twenty complete examples

These examples are intentionally independent. Replace IDs, locations, commands, and clip names for your
server. Run `/lsk reload` after editing YAML or adding audio.

### Example 1: a voiced stationary greeter

```text
/lsk create blacksmith shopkeeper Notch
/lsk name blacksmith <gold><b>Bruno the Blacksmith
```

Create `characters/blacksmith/dialog/default.yml`:

```yaml
nodes:
  greet:
    triggers: [approach, interact]
    cooldown: 15s
    lines:
      - { text: "Welcome, <player>!", voice: greet_1 }
      - { text: "The forge is hot today.", voice: greet_2, weight: 2 }
```

Add `greet_1.mp3` and `greet_2.mp3` to the character's voice folder.

### Example 2: day/night shopkeeper lines

```yaml
nodes:
  daytime:
    triggers: [approach]
    when: { time: day }
    lines: ["Fresh steel before sunset."]
  nighttime:
    triggers: [approach]
    when: { time: night }
    lines: ["The forge is still open, but keep your voice down."]
```

Only one of the two nodes can be selected for a given approach.

### Example 3: rain-specific welcome

```yaml
nodes:
  rain:
    triggers: [approach, interact]
    when: { weather: rain }
    cooldown: 30s
    lines:
      - { text: "Come in out of the rain.", voice: rain_1 }
```

### Example 4: react to a player holding an item

```yaml
nodes:
  miner:
    triggers: [interact]
    when: { holds: DIAMOND_PICKAXE }
    lines:
      - "That is a fine pickaxe. The deep mines will respect it."
```

### Example 5: VIP-only line

```yaml
nodes:
  vip:
    triggers: [interact]
    when: { permission: server.vip }
    weight: 3
    lines:
      - "Welcome back, valued member. The back shelf is open to you."
```

### Example 6: occasional ambient chatter

```yaml
nodes:
  ambient:
    triggers: [approach]
    when: { chance: 0.2 }
    cooldown: 45s
    lines:
      - "Business is strange today."
      - "I wonder what the caravans will bring tomorrow."
```

### Example 7: a menu-driven lore NPC

```yaml
nodes:
  start:
    title: "Ask the Archivist"
    triggers: [interact]
    options:
      - { text: "Tell me about the city", icon: BOOK, line: "It was built over an older ruin.", goto: city }
      - { text: "Who rules here?", icon: PLAYER_HEAD, line: "That depends who you ask.", goto: ruler }
      - { text: "Leave", icon: BARRIER }
  city:
    title: "The city"
    options:
      - { text: "One more question", icon: PAPER, goto: start }
      - { text: "Thanks", icon: EMERALD, line: "Read well, traveler." }
  ruler:
    title: "The ruler"
    options:
      - { text: "Back", icon: ARROW, goto: start }
```

### Example 8: a lobby warp NPC

```text
/lsk create ranks lobby Notch
/lsk lobby ranks post
/lsk action ranks add player:/ranks
/lsk tag ranks add <gradient:#00c6ff:#7b2ff7><b>RANKS</b></gradient>
```

Right-clicking the NPC runs `/ranks` as the player. Add more action lines to run several effects.

### Example 9: a lobby NPC with a seat

```text
/lsk lobby ranks seatwand
```

Right-click a slab or stair with the supplied wand to register it. The NPC can use it during idle wandering.
Sneak-right-click the same seat to remove it. Use `/lsk lobby ranks seats` to inspect the list.

### Example 10: a player-owned chest shop

```text
/lsk shopitem Alice 1
```

As Alice, hold the tagged item, right-click your stock chest, then right-click the nearby standing block. Click
the NPC, choose `Edit trades`, and build a trade such as 3 iron ingots → 1 emerald. The chest supplies the
emeralds and receives iron payments.

### Example 11: a two-cost admin shop trade

```text
/lsk create portal_shop shop Notch
```

Open the shop, choose `Edit trades`, and make a trade with Cost 1 = 1 emerald, Cost 2 = 1 ender pearl, Result
= 1 eye of ender. Admin shops have infinite stock because they have no owner chest.

### Example 12: a shop that welcomes customers

```yaml
nodes:
  greet:
    triggers: [greet]
    cooldown: 25s
    lines:
      - { text: "Welcome to my stall, <player>!", voice: welcome_1 }
  thanks:
    triggers: [thanks]
    cooldown: 8s
    lines:
      - { text: "Excellent choice.", voice: thanks_1 }
```

Put the file under the shop's character dialog directory. The shop behavior fires `greet` when a customer
enters/attends and `thanks` after a successful purchase.

### Example 13: shop idle gossip

```yaml
nodes:
  gossip:
    triggers: [gossip]
    cooldown: 20s
    lines:
      - "The miner down the road bought every lantern yesterday."
      - "I hear the market is busy after sunset."
```

Enable a reasonable `shop.gossip-chance` and `shop.gossip-range`; neighboring shop NPCs can occasionally
chat while idle and return to their customers when needed.

### Example 14: a patrolling barker

```text
/lsk create market_crier barker Cassie
/lsk barker market_crier home
/lsk barker market_crier addwaypoint
/lsk barker market_crier addwaypoint
/lsk barker market_crier addwaypoint
/lsk barker market_crier cooldown 10
```

At each waypoint, author an `invite` node with several weighted voice lines. Keep the node cooldown at or
below the desired re-greeting interval.

### Example 15: a barker with time-windowed places

```text
/lsk barker market_crier area market
/lsk barker market_crier visit tavern 18:00 02:00
/lsk barker market_crier sit gate 22:00 01:00
```

Then author:

```yaml
nodes:
  tavern_arrival:
    triggers: [arrive_tavern]
    lines: ["The tavern is lively tonight."]
  tavern_ambient:
    triggers: [ambient_tavern]
    cooldown: 45s
    lines: ["Looking for a drink or a deal?"]
```

### Example 16: barker invitation lifecycle

```yaml
nodes:
  call:
    triggers: [invite]
    cooldown: 8s
    lines:
      - { text: "Come see the new stock!", voice: invite_1 }
  success:
    triggers: [invite_success]
    lines:
      - { text: "That is it—come inside!", voice: welcome_1 }
  ignored:
    triggers: [invite_giveup]
    lines:
      - "Another time, then."
```

### Example 17: a shopkeeper that flees from attacks

```text
/lsk combat blacksmith flee
```

Add `hit_punch`, `hit_again`, `hit_weapon`, `flee`, and `calm` nodes. Tune `combat.flee-seconds` and
`combat.no-invite-after-hit-seconds` in config. The NPC will react, leave, and later settle back at home.

### Example 18: a guarded NPC with a harmless combat mode

```text
/lsk combat librarian none
```

This keeps reaction dialog available but prevents the NPC from fleeing or damaging players. For a completely
quiet server-wide setup, set `combat.enabled: false`.

### Example 19: voice-aware subtitles

```yaml
nodes:
  voice_users:
    triggers: [interact]
    when: { voicechat: connected }
    lines:
      - { text: "I can see your voice link is working.", voice: voice_ok }
  text_users:
    triggers: [interact]
    when: { voicechat: none }
    lines:
      - "I will keep this one in text so you do not miss it."
```

This lets the same NPC use a spoken line for connected players and a readable line for everyone else.

### Example 20: one NPC with a branded tag, action, and fallback greeting

```text
/lsk create helpdesk lobby Notch
/lsk name helpdesk <aqua><b>Help Desk
/lsk tag helpdesk add <gray>Click for assistance
/lsk action helpdesk add message:<yellow>Use /help or choose a category.
/lsk action helpdesk add sound:block.note_block.pling
```

If the character has actions, clicks execute them. If actions are later cleared, dialog/menu behavior resumes;
the configured fallback message is controlled by `messages.fallback-greet`.

For a complete large character package with multiple dialog files, branching menus, every supported condition,
place-specific barker content, combat reactions, and a voice inventory, see the [Mira full showcase](SHOWCASE.md).

## 11. PlaceholderAPI integration

When PlaceholderAPI is installed, the expansion name is `livingshopkeeper`. Current status placeholders are:

```text
%livingshopkeeper_voicechat%             connected | installed | none
%livingshopkeeper_has_voicechat%         yes | no
%livingshopkeeper_voicechat_connected%   yes | no
```

These are useful for a scoreboard hint such as “Voice: connected” or for choosing a server-wide help message.
They are separate from dialog `when.voicechat`, which evaluates the viewer directly.

## 12. Troubleshooting

### The NPC does not appear

Check that PacketEvents is installed and loaded before LivingShopkeeper. Run `/lsk list`, inspect the server
log for a PacketEvents error, and make sure the character's `world` exists. A packet NPC is viewer-specific;
test from a player within `hearing-distance`.

### The NPC appears but is silent

Check Simple Voice Chat, `LivingShopkeeperVoice`, `voice.enabled`, the player's voice connection, and the
server restart state. Confirm the clip is exactly under `characters/<id>/voice/` and that the `voice:` value
matches the filename without `.wav`/`.mp3`. Look for `Could not decode voice clip` in the log. Subtitles are
the fastest way to distinguish a dialog problem from an audio problem.

### Dialog never changes

Check YAML indentation, the exact trigger name, node/line cooldowns, and conditions. A global gap prevents two
lines from the same NPC reaching one player too quickly. Run `/lsk reload` and check the log's dialog node
count. If a file is malformed, the character's dialog can be skipped while other characters still load.

### The shop cannot be placed

Use the tagged item from `/lsk shopitem`, not a normal spawn egg. Right-click the player's own chest first,
then a nearby standing block within `shop.max-container-distance`. Check the permission and, when region
integration is present, ownership of the plot. The pending chest link expires after about one minute.

### A player shop has no stock

The linked chest is the stock source. Keep result items in it and make sure the chest is still at the saved
location. Use the owner GUI's `Move stock chest` control when relocating it; do not silently break and replace
the chest without relinking.

### The NPC is in the wrong place or too high while sitting

Use the shop GUI's move controls or `/lsk tp <id>` to inspect it. Adjust `seat-mount-offset` for seat height.
For lobby/barker seats, mark the actual slab/stair surface and remove/re-add an incorrect seat.

### A barker is not calling out

Confirm it is a `barker`, has a home/waypoints, is within its configured notice/invite distances, and has an
`invite` node. The invite node cooldown and barker cooldown both apply. A recently attracted, ignored, or hit
player can be temporarily suppressed by the backoff timers.

## 13. Authoring and deployment checklist

Before production:

- Install PacketEvents and perform a real restart.
- Create one test character and verify visibility, interaction, subtitles, and skin.
- Add one short voice clip and verify positional audio with two players at different distances.
- Test `/lsk reload` after changing a dialog file and after replacing a clip.
- Test shop ownership, chest stock, region protection, deletion, and a full chest.
- Set conservative combat behavior in protected/public regions.
- Keep dialog cooldowns high enough that an approach scan cannot spam chat.
- Validate every `voice:` ID against a file and every dialog `goto` against a menu node.
- Back up `plugins/LivingShopkeeper/characters/` and shop chests before bulk edits.
- Treat generated audio and any TTS credentials as separate deployment artifacts.

## 14. Current limitations and roadmap boundaries

The current implementation has trigger-driven dialog, GUI choices, packet NPCs, audio playback, shops, lobby
activity, barker movement/places, actions, tags, combat reactions, and voice-status conditions. The following
ideas appear in design/roadmap documents but should not be advertised as currently available without verifying
the exact jar: persistent reputation/mood-driven pricing, typed free-form conversation, player microphone
speech recognition, dynamic spoken prices/names, cross-NPC memory, and arbitrary informer behavior.

When extending the plugin, update this guide beside the code and keep the examples runnable against the jar
that is actually deployed.
