# LivingShopkeeper — Dialog Authoring Guide

> For the complete current feature guide, including shop creation, commands, NPC types, lobby/barker
> behavior, permissions, troubleshooting, and 20 examples, see [GUIDE.md](GUIDE.md). This file remains the
> shorter dialog/voice reference.

How to give a shopkeeper custom, **voiced**, contextual dialog. Everything is YAML + audio files under
`plugins/LivingShopkeeper/` — no code, hot-reloadable with `/lsk reload`.

> The examples below were written for the earlier flat layout. The current build uses
> `characters/<name>/{character.yml,dialog/,voice/}` and supports WAV or MP3. Use [GUIDE.md](GUIDE.md)
> for the current paths, shop/lobby/barker behavior, and complete command reference; translate the example
> YAML into the character's own `dialog/` folder when reusing it.

---

## TL;DR — 5 steps

1. Generate your voice lines and save each as **WAV or MP3** → `characters/<id>/voice/<clipId>.*`.
2. Write dialog → `characters/<id>/dialog/<file>.yml` (nodes → lines, each line names a `voice:` clip).
3. Create the character with `/lsk create <id>`; its dialog files are loaded from its own folder.
4. `/lsk reload` (picks up dialog + new/changed clips).
5. Right-click / walk up to the NPC → it speaks.

---

## File layout

```
plugins/LivingShopkeeper/
├── config.yml                 # subtitle-mode, hearing-distance, voice.distance …
├── shopkeepers/<id>.yml       # one per NPC — location, skin, which dialog pack it uses
├── dialog/<pack>.yml          # the lines (nodes) — share one pack across NPCs or give each its own
└── voice/<clipId>.wav         # the audio a line plays (48 kHz mono ideal; any rate is resampled)
```

---

## Step 1 — Voice clips (ElevenLabs → WAV)

The plugin plays **WAV** (decoded pure-Java, no ffmpeg). ElevenLabs' website download button gives **MP3**,
which the plugin can't read — so either:

- **API route (best):** request `output_format=wav` (or `pcm_24000` / `pcm_44100`) — you get WAV directly.
- **UI route:** download the MP3, then convert to WAV (Audacity → *Export as WAV*, or
  `ffmpeg -i line.mp3 -ar 48000 -ac 1 line.wav`).

Naming: the file's base name **is** the clip id. `voice/greeting.wav` → referenced in dialog as `voice: greeting`.
Mono, 48 kHz is ideal; other rates/channels are accepted and resampled. Keep lines short (a sentence or two).

> Want the plugin to read MP3/OGG directly? Say so — it's a small add (bundle a decoder). Until then: WAV.

---

## Step 2 — Write a dialog pack

A pack is a set of **nodes**. When a **trigger** fires, the engine gathers every node registered for that
trigger whose `when:` passes and that's off cooldown, **weight-picks one node**, then **weight-picks one of
its lines** (whose own `when:` passes), speaks the `voice:` clip and shows the `text:` as a subtitle.

```yaml
# dialog/blacksmith.yml
nodes:

  greet:
    triggers: [interact, approach]   # right-click AND walk-into-range
    cooldown: 12s                    # per-player: this node won't re-fire for 12s (kills approach-spam)
    weight: 3                        # how often this node wins vs other eligible nodes
    lines:
      - text: "Welcome, traveler! Best steel this side of the mountains."
        voice: bs_greet_1            # → voice/bs_greet_1.wav
        weight: 2                    # this line is 2× as likely as the others
      - text: "Ah, a customer. What'll it be, <player>?"
        voice: bs_greet_2
      - text: "Good <time> to you. Mind the hot metal."
        voice: bs_greet_day

  greet_night:
    triggers: [interact, approach]
    when: { time: night }            # only eligible at night
    cooldown: 12s
    lines:
      - { text: "Forge's still warm, friend. Out late?", voice: bs_night }

  rain_bark:
    triggers: [approach]
    when: { weather: rain }          # only when it's raining
    cooldown: 30s
    lines:
      - { text: "Come in out of the wet — plenty to see inside.", voice: bs_rain }

  browsing:
    triggers: [interact]             # only on right-click
    cooldown: 6s
    lines:
      - { text: "See anything you fancy?",              voice: bs_browse_1 }
      - { text: "Fair prices, I swear it on the anvil.", voice: bs_browse_2 }
      - { text: "Take your time.",                       voice: bs_browse_3 }
```

- A **bare string** works too: `lines: ["Hello there."]` (subtitle only, no voice).
- Omit `voice:` for a silent, subtitle-only line. Omit `text:` for a voice-only line (no subtitle).
- Multiple nodes can share a trigger — that's how you get variety: on each right-click a different eligible
  node/line is chosen, and cooldowns keep it from repeating itself.

---

## Step 3 — Point a shopkeeper at the pack

In `shopkeepers/<id>.yml` (created by `/lsk create <id>`):

```yaml
dialog:
  pack: blacksmith        # uses dialog/blacksmith.yml   (default: "default")
voice:
  greeting: bs_greet_1    # fallback clip if NO dialog node matches (optional)
```

Different NPCs can point at the same pack or their own. (Live command to set the pack in-game is coming; for
now edit the file + `/lsk reload`.)

---

## Step 4 — Reload

`/lsk reload` re-reads **config, shopkeepers, and dialog packs**. A **newly added** WAV is picked up on first
play. **Replacing** an existing clip's audio (same filename, new sound) needs a **server restart** — the
decoded clip is cached in memory. (Ask if you want a live voice refresh added to `/lsk reload`.)

---

## Reference

### Triggers (wired today)

| Trigger    | Fires when… |
|------------|-------------|
| `interact` | a player right-clicks the NPC |
| `approach` | a player walks into the NPC's range (`hearing-distance`, default 16) |

*(`buy_success`, `sell`, `haggle_*`, `farewell`, `first_meeting`, `returning`, … arrive with the shop (P4) and
memory (P5) phases.)*

### `when:` conditions (all optional; combine freely — all must pass)

| Key          | Values |
|--------------|--------|
| `time`       | `day` · `dusk` · `night` · `dawn` |
| `weather`    | `clear` · `rain` · `thunder` |
| `holds`      | a Bukkit material, e.g. `DIAMOND_SWORD` (player's main hand) |
| `chance`     | `0.0`–`1.0` (rolled each time; `0.25` = 25% eligible) |
| `permission` | a permission node the player must have |

`when:` on a **node** gates the whole node; `when:` on a **line** gates just that line.

### Placeholders (in `text:`)

`<player>` — the player's name · `<npc>` — the shopkeeper's name · `<time>` — morning / day / evening / night.
(The **voiced audio is fixed**; dynamic values only appear in the subtitle — so record lines that read fine
either way, e.g. "Good <time> to you.")

### Durations

`cooldown:` accepts `500ms`, `8s`, `2m`, `1h`, or a bare number = seconds. Default `5s`.

### Subtitle display

`config.yml` → `subtitle-mode: chat` (default) or `action_bar`. (`bubble` = floating text over the NPC — later;
falls back to chat.)

---

## Activating on a server

- **Voice needs a full restart.** Simple Voice Chat hands its server API over **once at startup**, so a
  PlugMan hot-load leaves the NPC mute until a real restart. Dialog *text/selection* works on a hot-load; the
  *audio* does not.
- On **qbit s5** (production, upload-only) the P3 jar is staged but won't run until the next restart. Author
  the `dialog/` and `voice/` files anytime; they load when it comes up.

---

## Planned Living Dialog Mode

The large conversation update—double-left-click entry, typed replies, multiplayer turn-taking, intent authoring,
memory and optimized graph compilation—is planned in [DIALOG_MODE.md](DIALOG_MODE.md). The YAML in this guide
continues to describe the currently implemented trigger/line engine until that roadmap lands.

## Barker places and contextual conversation

Each barker place gets its own dialog namespace. The commands use the admin's current location:

```text
/lsk barker cassie area club
/lsk barker cassie visit club 18:00 02:00
/lsk barker cassie sit lounge 22:00 01:30
/lsk barker cassie places
/lsk barker cassie removeplace lounge
```

`area` is passive: walking through it does not change the patrol route, but it selects that place's
conversation. `visit` sends the barker there during the Minecraft world-time window. `sit` does the same and
mounts the packet NPC on an invisible client-side seat; omitting the window makes it an all-day destination.
Windows may also be written as world ticks or `day`, `dusk`, `night`, and `dawn`. A window may wrap midnight.

For a place named `club`, author these optional triggers in that character's dialog pack:

```yaml
nodes:
  club_arrival:
    triggers: [arrive_club]
    lines: [{text: "The club is getting lively tonight.", voice: club_arrival}]
  club_ambient:
    triggers: [ambient_club]
    cooldown: 45s
    lines:
      - {text: "Need a drink or looking for the good crowd?", voice: club_bark_1}
      - {text: "Music's better inside. Trust me.", voice: club_bark_2}
  club_interact:
    triggers: [interact_club, approach_club]
    lines: [{text: "Welcome to the club.", voice: club_welcome}]
```

The same pattern works for an area the NPC merely passes through. Ambient lines are jittered and cooldown-gated;
they will not fire if nobody is nearby. Patrol hops are rare and only happen on the patrol route, while speaking
can produce an occasional arm swing, brief crouch, or small glance instead of a fixed gesture loop.

## Roadmap (not in this jar yet)

- **Per-character folders:** `characters/<name>/{character.yml, dialog/*.yml, voice/*.wav}` so each NPC is a
  self-contained folder (its own lines + its own ElevenLabs voice).
- **NPC types:** `shopkeeper` (this), **`barker`** (patrols a shop/ARM region, invites passers-by, backs off
  for hours once they enter), **`informer`** (later).
