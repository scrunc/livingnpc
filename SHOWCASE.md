# Full showcase character: Mira of the Many Roads

This is a complete, intentionally large LivingShopkeeper character package. Mira demonstrates identity,
skins, tags, actions, voice, subtitles, weighted dialog, cooldowns, every current condition, menu choices,
branching conversation, contextual lines, barker places, combat reactions, and a full voice inventory.

The package is split into files because the plugin merges every YAML file under one character's `dialog/`
directory. Copy it to a test server first, add the audio files, and remove content that does not fit your
server's tone.

## 1. Create the character

Stand at the desired location and run:

```text
/lsk create mira shopkeeper Alex
/lsk name mira <gradient:#55d6be:#4f7cac><b>Mira of the Many Roads</b></gradient>
/lsk tag mira add <gray>Travel, rumors, and useful things
/lsk tag mira add <yellow>Click to ask a question
/lsk action mira add message:<aqua>Mira listens. Choose a topic from her menu.
/lsk action mira add sound:entity.experience_orb.pickup
/lsk combat mira flee
```

Actions take priority over dialog when clicking. After testing the branded click response, clear the actions
so right-click opens Mira's dialog menu:

```text
/lsk action mira clear
```

Create this layout:

```text
plugins/LivingShopkeeper/characters/mira/
├── character.yml
├── dialog/
│   ├── 01_greetings.yml
│   ├── 02_conversation.yml
│   ├── 03_context.yml
│   ├── 04_places.yml
│   └── 05_combat.yml
└── voice/
    ├── greet_day.mp3
    ├── greet_day_2.mp3
    └── ...
```

## 2. `character.yml`

Keep the UUID and coordinates generated on your server. The rest can be edited as shown:

```yaml
type: shopkeeper
uuid: 2a8dc53d-5a5b-46d2-a6a3-9a10a6f8aa01
name: "Mira of the Many Roads"
display: "<gradient:#55d6be:#4f7cac><b>Mira of the Many Roads</b></gradient>"
world: world
x: 120.5
y: 72.0
z: -44.5
yaw: 180.0
pitch: 0.0

skin:
  name: Alex

voice:
  greeting: greet_day

tag:
  - "<gray>Travel, rumors, and useful things"
  - "<yellow>Click to ask a question"

# Optional; the commands above store the same data.
# actions:
#   - "message:<aqua>Mira listens. Choose a topic from her menu."
#   - "sound:entity.experience_orb.pickup"

combat:
  reaction: flee
```

## 3. `dialog/01_greetings.yml`

```yaml
nodes:
  morning_greeting:
    triggers: [greet, approach, interact]
    when: { time: dawn }
    cooldown: 25s
    weight: 3
    lines:
      - { text: "Morning, <player>. The roads are quiet, which is when they are most honest.", voice: greet_morning, weight: 3 }
      - { text: "You are up early, <player>. Looking for a safe road or an interesting one?", voice: greet_morning_2 }

  daytime_greeting:
    triggers: [greet, approach, interact]
    when: { time: day }
    cooldown: 25s
    weight: 5
    lines:
      - { text: "Welcome, <player>. Need a destination, a rumor, or a reason to leave?", voice: greet_day, weight: 3 }
      - { text: "The road brought you to me. That is usually a good sign.", voice: greet_day_2 }
      - { text: "Mira's map room is open. Try not to bleed on the expensive pages.", voice: greet_day_3 }

  dusk_greeting:
    triggers: [greet, approach, interact]
    when: { time: dusk }
    cooldown: 25s
    weight: 3
    lines:
      - { text: "Evening, <player>. Pick your destination before the dark picks it for you.", voice: greet_dusk }
      - { text: "The lamps are coming on. Honest travelers are checking their locks.", voice: greet_dusk_2 }

  night_greeting:
    triggers: [greet, approach, interact]
    when: { time: night }
    cooldown: 30s
    weight: 2
    lines:
      - { text: "Out late, <player>? The roads remember everyone who walks them at night.", voice: greet_night }
      - { text: "Keep your torch close. If you need a bed, ask before you need it desperately.", voice: greet_night_2 }
      - { text: "I am still awake. That does not mean the forest is.", voice: greet_night_3 }

  returning_style:
    triggers: [greet, interact]
    when: { chance: 0.18 }
    cooldown: 45s
    weight: 1
    lines:
      - { text: "Back again, <player>? I knew the road had not finished with you.", voice: return_1 }
      - { text: "You have the look of someone who found exactly the wrong shortcut.", voice: return_2 }

  approach_whisper:
    triggers: [approach]
    when: { chance: 0.12 }
    cooldown: 50s
    lines:
      - "The map on the left is upside down. It is not a mistake."
      - "If you hear bells beyond the north gate, do not follow them alone."
      - { text: "The roads are listening today.", voice: ambient_road }

  voice_connected:
    triggers: [interact]
    when: { voicechat: connected, chance: 0.2 }
    cooldown: 60s
    lines:
      - { text: "Your voice link is clear. I can speak softly, then.", voice: voice_connected }

  voice_missing:
    triggers: [interact]
    when: { voicechat: none, chance: 0.2 }
    cooldown: 60s
    lines:
      - "I will keep this in text so you do not miss anything important."

  vip_welcome:
    triggers: [interact]
    when: { permission: server.vip, chance: 0.5 }
    cooldown: 90s
    weight: 2
    lines:
      - { text: "The private routes are available to you, <player>.", voice: vip_welcome }
      - "I have set aside maps that never reach the public shelf."
```

## 4. `dialog/02_conversation.yml`

Right-click opens `start`. Options can display a subtitle with `line`, play a clip with `say`, and continue
with `goto`. A target must be another menu node to continue; otherwise the GUI closes.

```yaml
nodes:
  start:
    title: "Mira's Road Desk"
    triggers: [interact]
    cooldown: 3s
    options:
      - { text: "Where can I travel?", icon: COMPASS, line: "Tell me what kind of trouble you want.", goto: destinations }
      - { text: "Tell me a rumor", icon: BOOK, line: "Rumors cost less than maps and cause more damage.", goto: rumors }
      - { text: "Ask about the weather", icon: SUNFLOWER, line: "The sky has been suspicious all morning.", goto: weather_menu }
      - { text: "Who are you?", icon: PLAYER_HEAD, line: "A guide, a collector, and a person who dislikes dead ends.", goto: about_mira }
      - { text: "I need help", icon: PAPER, line: "Then start with one clear question.", goto: help_menu }
      - { text: "Goodbye", icon: BARRIER, line: "Safe roads, <player>." }

  destinations:
    title: "Choose a destination"
    options:
      - { text: "The market", icon: EMERALD, say: market_reply, line: "The market is east, past the lantern posts.", goto: market_followup }
      - { text: "The old mine", icon: IRON_PICKAXE, say: mine_reply, line: "Take food, a spare pick, and a plan for getting out.", goto: mine_followup }
      - { text: "The forest", icon: OAK_SAPLING, say: forest_reply, line: "Stay on the marked path until the trees stop whispering.", goto: forest_followup }
      - { text: "Back", icon: ARROW, goto: start }

  market_followup:
    title: "The market road"
    options:
      - { text: "What should I buy?", icon: CHEST, line: "Rope, bread, and one thing you will not admit you need.", goto: market_supplies }
      - { text: "Back", icon: ARROW, goto: destinations }
      - { text: "Thanks", icon: EMERALD, line: "Spend carefully." }

  market_supplies:
    title: "Market supplies"
    options:
      - { text: "Where is the nearest shop?", icon: VILLAGER_SPAWN_EGG, line: "Follow the colored banners and listen for haggling.", goto: start }
      - { text: "Back", icon: ARROW, goto: market_followup }

  mine_followup:
    title: "The old mine"
    options:
      - { text: "Is it dangerous?", icon: SHIELD, line: "Only if you enter believing the warnings are decoration.", goto: mine_danger }
      - { text: "What is inside?", icon: DIAMOND, line: "Ore, echoes, and a door no one remembers building.", goto: mine_danger }
      - { text: "Back", icon: ARROW, goto: destinations }

  mine_danger:
    title: "A serious warning"
    options:
      - { text: "I will prepare", icon: SHIELD, line: "Preparation is the cheapest armor.", goto: start }
      - { text: "I am going anyway", icon: IRON_SWORD, line: "Then leave a note somewhere that can be found.", goto: start }

  forest_followup:
    title: "The forest road"
    options:
      - { text: "Any landmarks?", icon: OAK_LOG, line: "A split cedar, a dry stream, and a stone facing the wrong way.", goto: forest_landmarks }
      - { text: "Are there monsters?", icon: SPIDER_EYE, line: "There are always monsters. Ask whether they are hungry.", goto: forest_landmarks }
      - { text: "Back", icon: ARROW, goto: destinations }

  forest_landmarks:
    title: "Forest landmarks"
    options:
      - { text: "I understand", icon: MAP, line: "Mark your way home before the trees look identical.", goto: start }
      - { text: "Back", icon: ARROW, goto: forest_followup }

  rumors:
    title: "Rumor shelf"
    options:
      - { text: "The northern bells", icon: BELL, line: "They ring when nobody pulls the rope.", goto: bell_rumor }
      - { text: "The empty ship", icon: OAK_BOAT, line: "It arrived with wet footprints and no crew.", goto: ship_rumor }
      - { text: "The smiling statue", icon: STONE, line: "Do not smile back. That is how it knows you noticed.", goto: statue_rumor }
      - { text: "Back", icon: ARROW, goto: start }

  bell_rumor:
    title: "Northern bells"
    options:
      - { text: "Who heard them?", icon: PLAYER_HEAD, line: "Three guards. They agree about nothing except the sound.", goto: rumors }
      - { text: "That is enough", icon: BARRIER, line: "Wise. A rumor should never become a schedule." }

  ship_rumor:
    title: "The empty ship"
    options:
      - { text: "Where is it now?", icon: OAK_BOAT, line: "At the river bend. It leaves when someone boards.", goto: rumors }
      - { text: "I will avoid it", icon: BARRIER, line: "A rare and sensible decision." }

  statue_rumor:
    title: "The statue"
    options:
      - { text: "Where can I see it?", icon: STONE, line: "You cannot. That is why it is still smiling.", goto: rumors }
      - { text: "No more rumors", icon: BARRIER, line: "Then we agree on something important." }

  weather_menu:
    title: "Read the sky"
    options:
      - { text: "Clear skies", icon: SUNFLOWER, line: "Clear skies make travelers overconfident.", goto: start }
      - { text: "Rain", icon: WATER_BUCKET, line: "Rain makes tracks honest and roads unpleasant.", goto: start }
      - { text: "Thunder", icon: LIGHTNING_ROD, line: "Stay near shelter and do not hold metal over your head.", goto: start }
      - { text: "Back", icon: ARROW, goto: start }

  about_mira:
    title: "About Mira"
    options:
      - { text: "Where were you born?", icon: MAP, line: "Beside a road that no longer exists.", goto: about_mira_2 }
      - { text: "Why collect rumors?", icon: BOOK, line: "Maps show where people go. Rumors show why.", goto: about_mira_2 }
      - { text: "Back", icon: ARROW, goto: start }

  about_mira_2:
    title: "One last answer"
    options:
      - { text: "That is poetic", icon: PAPER, line: "It is also inconveniently true.", goto: start }
      - { text: "Back", icon: ARROW, goto: about_mira }

  help_menu:
    title: "What kind of help?"
    options:
      - { text: "I am lost", icon: COMPASS, line: "Look at the sun, then find my blue road markers.", goto: start }
      - { text: "I need supplies", icon: CHEST, line: "Ask the shopkeeper with the red awning.", goto: start }
      - { text: "I need a warning", icon: SHIELD, line: "Never trust a quiet road with fresh footprints.", goto: start }
      - { text: "Cancel", icon: BARRIER }
```

## 5. `dialog/03_context.yml`: every current condition

```yaml
nodes:
  clear_day:
    triggers: [interact]
    when: { time: day, weather: clear }
    cooldown: 40s
    lines: [{ text: "The clear sky is inviting today.", voice: context_clear }]

  rain_day:
    triggers: [interact]
    when: { time: day, weather: rain }
    cooldown: 40s
    lines: [{ text: "Rain has turned the east road into soup.", voice: context_rain }]

  thunder_warning:
    triggers: [interact]
    when: { weather: thunder }
    cooldown: 30s
    lines: [{ text: "Thunder. Stay under a roof until the sky calms down.", voice: context_thunder }]

  sword_holder:
    triggers: [interact]
    when: { holds: DIAMOND_SWORD }
    cooldown: 35s
    lines: [{ text: "That sword has seen work. Are you going somewhere dangerous?", voice: context_sword }]

  pick_holder:
    triggers: [interact]
    when: { holds: DIAMOND_PICKAXE }
    cooldown: 35s
    lines: [{ text: "A good pick opens more roads than a good horse.", voice: context_pick }]

  vip_context:
    triggers: [interact]
    when: { permission: server.vip, chance: 0.5 }
    cooldown: 60s
    lines:
      - { text: "The private map is still where I told you it would be.", voice: context_vip }

  voice_none:
    triggers: [interact]
    when: { voicechat: none, chance: 0.35 }
    cooldown: 60s
    lines: ["I will write down the important part. The east road is safe until dusk."]

  voice_installed_not_connected:
    triggers: [interact]
    when: { voicechat: installed, chance: 0.25 }
    cooldown: 60s
    lines: ["Your voice mod is installed, but it is not connected. Text is fine."]

  rare_lucky_line:
    triggers: [approach]
    when: { chance: 0.03 }
    cooldown: 5m
    lines: [{ text: "Today is a good day to take the road you avoid.", voice: rare_lucky }]

  mixed_weather:
    triggers: [interact]
    cooldown: 20s
    lines:
      - { text: "A normal day.", when: { weather: clear } }
      - { text: "Carry an umbrella.", when: { weather: rain } }
      - { text: "Find shelter.", when: { weather: thunder } }
      - "The road is always there, whatever the sky does."
```

The supported condition keys are `time`, `weather`, `holds`, `chance`, `permission`, and `voicechat`.
Conditions on a node filter the whole node; conditions on a line filter only that line. All conditions in
one `when` map must pass.

## 6. `dialog/04_places.yml`: barker-compatible place talk

These triggers are used when the same content is copied to a barker character with configured places. A
stationary shopkeeper will not emit place triggers automatically.

```yaml
nodes:
  market_arrival:
    triggers: [arrive_market]
    cooldown: 20s
    lines: [{ text: "The market is loud today. That usually means money is moving.", voice: market_arrive }]

  market_ambient:
    triggers: [ambient_market]
    cooldown: 45s
    lines:
      - { text: "Fresh bread, cheap lanterns, and at least one dishonest scale.", voice: market_ambient_1, weight: 2 }
      - { text: "If a seller says final price, ask whether they mean the first final price.", voice: market_ambient_2 }

  market_interact:
    triggers: [approach_market, interact_market]
    cooldown: 18s
    lines: [{ text: "Welcome to the market, <player>.", voice: market_welcome }]

  tavern_arrival:
    triggers: [arrive_tavern]
    when: { time: night }
    cooldown: 25s
    lines: [{ text: "The tavern is warm, the beds questionable, and the soup brave.", voice: tavern_arrive }]

  tavern_ambient:
    triggers: [ambient_tavern]
    cooldown: 50s
    lines:
      - "Do not challenge the quiet person at the corner table."
      - { text: "The best stories start after the second cup.", voice: tavern_ambient }

  gate_sit:
    triggers: [arrive_gate, ambient_gate]
    cooldown: 60s
    lines: [{ text: "I like this gate. Everyone going somewhere must pass it.", voice: gate_ambient }]
```

Configure a separate moving crier:

```text
/lsk create mira_crier barker Alex
/lsk barker mira_crier home
/lsk barker mira_crier addwaypoint
/lsk barker mira_crier addwaypoint
/lsk barker mira_crier area market
/lsk barker mira_crier visit tavern 18:00 02:00
/lsk barker mira_crier sit gate 22:00 01:00
```

Copy the place file into `characters/mira_crier/dialog/` and add `invite`, `invite_success`, and
`invite_giveup` nodes as shown below:

```yaml
nodes:
  invite:
    triggers: [invite]
    cooldown: 10s
    lines:
      - { text: "Come see the new stock!", voice: invite_1 }
      - { text: "The market has something worth your time today.", voice: invite_2 }
  invite_success:
    triggers: [invite_success]
    lines: [{ text: "That is it. Come inside and look around.", voice: invite_success }]
  invite_giveup:
    triggers: [invite_giveup]
    lines: [{ text: "Another time, then. The road is patient.", voice: invite_giveup }]
```

## 7. `dialog/05_combat.yml`: complete reaction set

```yaml
nodes:
  first_punch:
    triggers: [hit_punch]
    cooldown: 2s
    lines:
      - { text: "Ow! Hands away from the map table!", voice: hit_punch_1 }
      - { text: "That was unnecessary and poorly aimed.", voice: hit_punch_2 }
      - "Please stop hitting the person who knows the roads."

  repeated_punch:
    triggers: [hit_again]
    cooldown: 2s
    lines:
      - { text: "I asked once. I am asking less politely now.", voice: hit_again_1 }
      - { text: "One more hit and I leave you to navigate alone.", voice: hit_again_2 }

  weapon_warning:
    triggers: [hit_weapon, hit]
    cooldown: 2s
    lines:
      - { text: "Put the weapon away! This is a conversation desk!", voice: hit_weapon_1 }
      - { text: "Steel is a poor argument and an expensive repair.", voice: hit_weapon_2 }

  flee:
    triggers: [flee]
    cooldown: 5s
    lines:
      - { text: "I am leaving. The road is safer than this conversation.", voice: flee_1 }
      - { text: "You have mistaken a guide for a target!", voice: flee_2 }

  fightback:
    triggers: [fightback]
    cooldown: 5s
    lines: [{ text: "Enough. I have survived worse roads than you.", voice: fightback_1 }]

  fight_hit:
    triggers: [fight_hit]
    cooldown: 4s
    lines: [{ text: "That was for the map table.", voice: fight_hit_1 }]

  calm:
    triggers: [calm]
    cooldown: 8s
    lines:
      - { text: "Good. The road is quiet again.", voice: calm_1 }
      - "Let us both remember this as a poor detour."
```

Use `/lsk combat mira flee` for fleeing, `/lsk combat mira fightback` for scripted retaliation, or
`/lsk combat mira none` to keep reactions but disable movement/damage.

## 8. Voice inventory

Every referenced voice ID should have a matching file under `characters/mira/voice/`. The extension can be
`.mp3` or `.wav`; subtitle-only lines do not need a file.

```text
greet_morning.mp3       greet_morning_2.mp3
greet_day.mp3           greet_day_2.mp3          greet_day_3.mp3
greet_dusk.mp3          greet_dusk_2.mp3
greet_night.mp3         greet_night_2.mp3        greet_night_3.mp3
return_1.mp3            return_2.mp3             ambient_road.mp3
voice_connected.mp3     vip_welcome.mp3
market_reply.mp3        mine_reply.mp3           forest_reply.mp3
context_clear.mp3       context_rain.mp3         context_thunder.mp3
context_sword.mp3       context_pick.mp3         context_vip.mp3
rare_lucky.mp3          market_arrive.mp3        market_ambient_1.mp3
market_ambient_2.mp3    market_welcome.mp3       tavern_arrive.mp3
tavern_ambient.mp3      gate_ambient.mp3         invite_1.mp3
invite_2.mp3            invite_success.mp3       invite_giveup.mp3
hit_punch_1.mp3         hit_punch_2.mp3          hit_again_1.mp3
hit_again_2.mp3         hit_weapon_1.mp3         hit_weapon_2.mp3
flee_1.mp3              flee_2.mp3               fightback_1.mp3
fight_hit_1.mp3         calm_1.mp3
```

## 9. Testing sequence

1. Create Mira and confirm `/lsk list` shows `mira [shopkeeper]`.
2. Save the character and dialog files as UTF-8.
3. Add one greeting clip and run `/lsk reload`; check the dialog/voice counts in the server log.
4. Walk into Mira and confirm a subtitle appears.
5. Right-click and test every conversation branch and `goto` destination.
6. Test dawn, day, dusk, night, clear, rain, and thunder conditions.
7. Test a diamond sword and diamond pickaxe in the main hand.
8. Test the `server.vip` permission with two players.
9. Test connected voice, installed-but-disconnected voice, and no voice mod.
10. Hit Mira once, repeatedly, and with a weapon; verify the configured reaction.
11. Add the remaining clips and reload again.
12. Back up `characters/mira/` after approval.

## 10. Making Mira a real shop

Dialog and item trading are separate systems. For a trading version, create a second character:

```text
/lsk create mira_shop shop Alex
```

Open the shop, choose `Edit trades`, and build trades through the GUI. Copy the shop dialog nodes into
`characters/mira_shop/dialog/` and use `greet`, `interact`, `thanks`, `farewell`, and `gossip`. An admin shop
has infinite stock. A player-owned shop uses the tagged creation item, a linked chest, and region ownership.

Do not advertise future-only features such as reputation conditions, dynamic spoken prices, microphone
speech-to-text, or informer behavior as available unless the deployed jar explicitly supports them.

## 11. Spaces, outings and the ghost head (2026-08-02)

Shared hangout **spaces** replace the old barker `visit`/`sit` places (those commands now redirect;
`area` dialog zones still work). Any post-holding NPC — lobby, shop, barker — can go on an **outing**:

```text
/lsk space create plaza                # centre = where you stand
/lsk space wand plaza                  # then right-click slabs/stairs to add seats (sneak-click removes)
/lsk space list · tp plaza · remove plaza

/lsk away mira_shop                    # go now (config outings.default-minutes)
/lsk away mira_shop 10 plaza           # 10 minutes at 'plaza'
/lsk back mira_shop                    # recall
/lsk away mira_shop tag add <gray>Off duty — down at the plaza
/lsk away mira_shop space plaza       # preferred space   ·  minutes <n> = default length
```

While out, a slowly spinning **ghost head** (the NPC's own skin) floats at the post, showing its tag plus
`outings.placeholder-line` ("not available right now"). Clicking or punching the head behaves exactly like
clicking the NPC — a shop still opens its trade window. The NPC walks to the space when it is within
`outings.walk-max-distance`, otherwise it fades out and reappears there. Seats are claimed per space, so
two visitors never share a stair. An away tag (`away.tag` in character.yml) replaces the floating tag
while out and is restored on return.

**Scheduling** lives in `config.yml → outings.schedules` — e.g. the shipped `night-out` sends each shop
NPC out with 50% probability every Minecraft night, staggered, returning at dawn or when its stay ends.
Per-NPC opt-out: `ai.outings: false`.

**Conversation beats** (gossip 2.0): shop-front gossip and space hangouts are turn-taking now — the
speaker turns and gestures, the partner nods, chuckles (crouch-bob + villager note) or waves back, and
lines alternate. Dialog triggers: `gossip_start`, `gossip`, `gossip_reply`, `gossip_end`, `space_chat`,
plus outing triggers `leave_post`, `arrive_space`, `back_at_post`. With no authored line, a random
`gossip.smalltalk` line from config is shown to nearby players (with `<partner>` substitution), so a
conversation is never invisible.

## 12. Group outings, AI chatter, and sparring (2026-08-03)

**No more teleporting**: `outings.walk-max-distance: -1` (the new default) makes NPCs walk the whole way
to a space, however far. Set a positive block count only if you want the old fade-out for distant spaces.

**Group trips**: when a scheduled outing fires, nearby idle colleagues can tag along
(`outings.group-chance` / `max-group` / `buddy-range`): they walk side-by-side behind the leader,
chat on the way (`stroll_chat` dialog trigger — give each character its own voiced lines), and claim
adjacent seats at the space. Seat claims now guarantee one NPC = one chair.

**AI-generated chatter** (`ai-chat`, OFF by default): when a conversation beat has no authored line,
an LLM writes the next line instead of the static pool. Works with any OpenAI-compatible endpoint —
OpenRouter and Cerebras ship preconfigured; add keys under `ai-chat.providers`. Multiple keys AND
multiple models per provider: a failing combo cools down 60s and the next is tried; if everything
fails the static smalltalk pool speaks. Give each character a voice with:

```text
/lsk persona mira a well-travelled trinket trader, warm but always haggling
```

Conversations keep a short transcript for continuity; global `requests-per-minute` and per-conversation
`min-interval-seconds` caps keep cost and CPU negligible (fully async, never blocks the server thread).

**Sparring** (`spar` config + per-NPC opt-in): a duel-partner NPC that never attacks first.

```text
/lsk spar mira on
/lsk spar mira region arena_main     # optional: only spar players standing in this WG region
```

Hit it and it mirrors your weapon — sword answers sword, mace answers mace, bow answers bow (it kites
6–16 blocks and shoots real arrows). Stop fighting for `spar.idle-end-seconds` (or walk off) and it
sheathes, says its `spar_end` line, and walks home. Triggers: `spar_start` · `spar_hit` · `spar_end`.
