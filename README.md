# AntiMod

Silent client-mod detection for Paper 1.21.6, using the sign / translation-key exploit. **Fully compatible with Folia**.

Sends a phantom sign to the client during the join sequence, packed with translation keys for banned mods. The client either resolves the key (mod is installed) or falls back to the marker (mod is absent). The client's `UPDATE_SIGN` reply reveals which mods are present. The join sweep runs as soon as the client has loaded the world (a client still on the loading screen has no chunks and drops the signs).

Inspired by the technique used on DonutSMP.

## Requirements

- **Paper 1.21.6** (also works on later 1.21.x; other versions untested)
- **PacketEvents 2.13.0+** — https://github.com/retrooper/packetevents/releases
- **Java 21**
- **Floodgate** (optional) — for detecting mods on Bedrock/Xbox players via Geyser

## Installation

1. Drop `AntiMod-1.1.0.jar` into `plugins/`.
2. Ensure `PacketEvents` is also in `plugins/`.
3. Start the server once to generate `plugins/AntiMod/config.yml`.
4. Edit `config.yml` to configure the mods and actions you want.
5. `/antimod reload` (or restart) to apply changes.

## How it works

1. When a player joins (or `/antimod check <player>` is run), the plugin sends this packet sequence:
   - `BLOCK_CHANGE` at a configured phantom position, claiming an oak sign is there
   - `TILE_ENTITY_DATA` populating the sign with translation-component text like `{"translate":"key.meteor-client.open-gui","fallback":"am_fb"}`
   - `OPEN_SIGN_EDITOR` for that position
   - `CLOSE_WINDOW` immediately after
2. The client renders the sign editor with each line resolved through its own language files. If a mod is installed and has that translation key, the client renders the mod's translated string. If not, the client renders the fallback (`am_fb`).
3. When the sign editor is force-closed, the client submits the current sign contents via `UPDATE_SIGN`.
4. AntiMod intercepts that packet and compares each line to the fallback marker. Anything else = the mod is present.
5. The plugin records a violation and runs the configured action(s) for that mod at the current violation count.

## Configuration highlights

Everything is in `config.yml`. Key knobs:

```yaml
detection:
  on-join: true              # probe every player on join
  join-delay-ticks: 0        # extra delay after the client has loaded the world
  periodic:
    enabled: false           # re-probe online players periodically
    interval: 600            # ...every 10 minutes
  sign-y-offset: 100         # phantom sign 100 blocks above the player
                             # (falls back below if it would hit the height cap)
  fallback-marker: "am_fb"   # anything else means "mod is present"

mods:
  meteor-client:             # instant ban - severe cheat client
    display-name: "Meteor Client"
    keys:
      - "key.meteor-client.open-gui"
    actions:
      - threshold: 1
        commands:
          - "litebans:ban {player} 25d Cheating ({mod})"
        broadcast: "&c[AntiMod] &e{player} &cbanned for using &4{mod}&c!"

  freecam:                   # warn twice, then ban
    display-name: "Freecam"
    keys:
      - "key.freecam.toggle"
    actions:
      - threshold: 1
        commands:
          - "kick {player} &cRemove {mod} before rejoining!"
      - threshold: 2
        commands:
          - "kick {player} &cFinal warning: remove {mod}!"
      - threshold: 3
        commands:
          - "litebans:ban {player} 14d Cheating ({mod}) - Repeated warnings"
```

## Spoof detection (OpSec, ExploitPreventer and similar)

Some client mods stop translation keys from resolving on signs, so every probe comes back as the fallback and banned mods go unseen. AntiMod cross-checks those answers against the client's **plugin channels**:

1. On join, a modded client announces the channels its mods listen on (`minecraft:register`). A channel like `voicechat:secret` proves Simple Voice Chat is installed.
2. The join sweep waits for that announcement, then probes the banned mods' keys **plus** the keys of every mod whose channel was seen.
3. A real install always resolves its own keys. If the channel is there but every key comes back as the fallback, the client is hiding translations and is flagged as `translation-spoofer`.

A client that echoes a probed key back verbatim is flagged too. A vanilla client always answers with the fallback we supply, never the raw key.

The channel → key list lives in `plugins/AntiMod/spoof-mods.yml`. It ships with entries generated from the most downloaded mods on Modrinth, and you can add your own:

```yaml
mods:
  simple-voice-chat:
    display-name: "Simple Voice Chat"
    channels:
      - "voicechat:*"          # * matches anything
    keys:                      # any ONE resolving clears the mod
      - "key.voice_chat"
      - "key.voice_chat_group"
      - "key.category.voicechat.voicechat"
```

A client that hides translations hides them for every mod. So a player is only flagged when the channel-confirmed mods that hid all their keys **outnumber** the ones that resolved theirs. If most mods verify, the player is fine even if a few didn't. By default every detection kicks the player and alerts staff.

**Trusted entries (max priority).** An entry with `trusted: true` skips that vote: if its keys are hidden the player is flagged straight away, and if they verify the player is cleared. Trusted entries are always probed and never auto-disabled. OpSec lets most channel mods' keys through but still hides a few, so these ship trusted:

| Entry | Channel | Why it's safe to trust |
|---|---|---|
| Plasmo Voice | `plasmo:*` | Every 2.x build (74 sampled) has `key.plasmovoice.general`/`settings`; every 1.x build (25, MC 1.16.4–1.19.3) has `key.plasmo_voice.ptt`/`mute`. |
| Customizable Player Models | `cpm_net:*` | All 134 sampled builds that use networking have `key.cpm.gestureMenu`, `key.cpm.category` and `key.cpm.qa_1`. |
| Collective | `collective:collective_installed_packet` | Only Collective 8.27+ registers this channel, and all 109 of those builds ship the keys. Older Collective has no channel or lang file of its own and never matches. |

Only trust an entry after checking that **every** build that registers the channel ships the keys. A wrong trusted entry kicks everyone who uses that mod.

When an entry fails for **5 different players** who verified every other mod in the same sweep, the entry is the problem, not the players. AntiMod comments it out in `spoof-mods.yml` with a note explaining why and reloads the list. The tallies live in `spoof-mod-misses.yml`, and `auto-disable-after` sets the count.

`/antimod channels <player>` shows what a client registered and which entries those channels match, which makes it easy to add a new mod. Settings and actions are under `spoof-detection` in `config.yml`.

### False-positive safeguards

- **Proxy channels:** Velocity registers its own plugins' channels on the backend for every player. For example, voicechat-velocity registers `voicechat:secret` and `voicechat:request_secret`, which would make vanilla players look like they run Simple Voice Chat. Those channels are excluded by `ignore-channels`. Any channel a vanilla-brand client carries is learned and ignored automatically (`learn-proxy-channels`). Vanilla-brand and Bedrock (Floodgate) players are never spoof-checked.
- **Old clients:** before 1.19.4, clients ignore the translation `fallback` and show the raw key for unknown keys. A raw-key answer is therefore never counted as proof of any mod, banned or spoof-check. It only counts as tampering for Java 1.19.4+ clients (version read through ViaVersion when present).
- **Majority rule:** hidden mods must outnumber verified ones. On top of that, by default `min-hidden-mods: 2`, so one odd mod build or one stray channel can't flag anyone.
- **Self-cleaning list:** entries that keep failing for players who verify everything else are disabled automatically (see above).
- **Keys that exist in every build:** each key in `spoof-mods.yml` is present in **every** sampled build of its mod (Fabric, Quilt, Forge and NeoForge, MC 1.16.5+, newest and oldest build per MC release). It isn't a vanilla key, no other mod ships it, and its value is non-empty and safe in every language.

**Limitation:** with their default settings, OpSec (AUTO whitelist) and ExploitPreventer let the keys of most channel-registering mods resolve, so the majority vote alone would clear them. A default OpSec install is caught through the trusted entries above, which it still hides. ExploitPreventer credits a mod's keys to any mod that registers its own network channel, so it hides none of them: use the pack check below. A client running none of those mods is caught only when its setup blocks keys for enough other mods whose channels are visible.

**Proxy plugins:** a Velocity plugin that registers a trusted entry's channel (for example a Plasmo Voice proxy add-on) would put that channel on every player. Add its channels to `ignore-channels` before installing it.

## Mod scan (`/antimod mods <player>`)

`/antimod mods <player>` probes a player for every mod in `plugins/AntiMod/mod-catalog.yml`, plus the banned mods, and lists:

- **Detected by translation keys:** mods whose key resolved on the client.
- **Expected from channels:** the mods the player's plugin channels say they have, marked *verified* if their keys resolved, *keys hidden* if every key came back untranslated, *not in catalog* if there's no key for them, or *no answer*.
- **Channels from unlisted mods:** channel namespaces that no catalog entry accounts for.

The catalog is generated from the most downloaded client-side mods on Modrinth and the most downloaded mods on CurseForge, with one key per mod. Each key is present in every jar of its mod that was read, isn't a vanilla key, and isn't shipped by any other listed mod. A scan only reports: it never records violations or runs actions. The whole scan goes out at once and finishes in about a second; add `--slow` to spread it over about 10 seconds (`mod-scan.slow-signs-per-tick`) for players on weak machines or connections. Three keys share each sign line (`mod-scan.keys-per-line`), each tagged with a marker so the answer can be split back up, so the full catalog fits in roughly 400 signs. That matters because the client answers every sign with a packet, and ViaVersion kicks clients that send more than 800 a second. The signs sit in a small grid of columns above the player.

## Pack check (`/antimod check <player> --pack`)

Catches ExploitPreventer, which the key probes can't. ExploitPreventer replaces Java's redirect handling for server resource-pack downloads with its own loop, and that loop gives up one redirect early: Java follows up to 19 redirects, ExploitPreventer only 18. AntiMod serves a tiny empty pack behind exactly 19 redirects from a small HTTP server of its own, pushes it to the one player, and compares their answer with the requests its server actually saw:

| Client answer | Verdict |
|---|---|
| Downloaded (or loaded) | clean |
| Download failed, although the final hop was served | ExploitPreventer detected |
| Declined, no answer, or failed before the final hop | inconclusive |

Players whose server resource-pack setting is *Prompt* see a pack prompt, plus a short resource reload if they accept. With the command, the pack is removed again straight after the answer and the result goes to whoever ran it. Set `pack-check.run-actions: true` to also run the spoof-detection actions (kick by default) on a detection.

**On join:** `pack-check.on-join.enabled: true` checks every Java player automatically once their client has loaded the world (plus `delay-ticks`). `required: true` makes the pack required, so a player who declines is disconnected by their own client. On-join detections go to staff with `antimod.notify` and the console. By default the empty pack stays on the client afterwards (`keep-pack`), since removing it costs another resource reload.

**Other server resource packs** are left alone: the check pack is added next to them under its own id, and only that id is ever removed. Clients before 1.20.3 can hold just one server pack, so they are never sent it (it would replace yours); ExploitPreventer needs a newer client anyway.

Setup: the HTTP server needs a port players can reach. On Pterodactyl, add an allocation, then set `pack-check.port` and `pack-check.public-url` (e.g. `http://play.example.com:25590`) in `config.yml` and restart.

### Placeholders

Available in all `commands`, `broadcast`, and `message` fields:

| Placeholder    | Meaning                                     |
|----------------|---------------------------------------------|
| `{player}`     | Player name                                 |
| `{uuid}`       | Player UUID                                 |
| `{mod}`        | Configured `display-name`                   |
| `{mod-id}`     | The mod's config key (e.g. `meteor-client`) |
| `{violations}` | Current violation count for this mod        |
| `{keys}`       | The translation keys that matched           |

MiniMessage-style `<player>`, `<mod>`, etc. also work in the `staff-message` field.

## Commands

| Command                             | Description                              |
|-------------------------------------|------------------------------------------|
| `/antimod reload`                   | Reload `config.yml`                      |
| `/antimod check <player> [--pack]`  | Probe a player right now; `--pack` runs the ExploitPreventer pack check instead |
| `/antimod violations <player>`      | Show a player's per-mod violation counts |
| `/antimod clear <player> [mod]`     | Clear violations (all mods, or one)      |
| `/antimod info`                     | List configured mods                     |
| `/antimod channels <player>`        | Show a player's registered plugin channels |
| `/antimod mods <player> [--slow]`   | Scan a player for known mods (report only); `--slow` spreads it over ~10 s |

Alias: `/am`

## Permissions

| Node               | Default | Purpose                          |
|--------------------|---------|----------------------------------|
| `antimod.admin`    | op      | Access to `/antimod` subcommands |
| `antimod.bypass`   | false   | Skip mod detection entirely      |
| `antimod.notify`   | op      | Receive in-game detection alerts |

## Adding a new mod

You only need the translation key(s) the mod ships. Find them by:

- Extracting the mod jar and looking at `assets/<modid>/lang/en_us.json`
- Or watching packets while a test account with the mod joins (the mod's language file will be loaded client-side)

Then add an entry under `mods:`:

```yaml
mods:
  my-new-mod:
    display-name: "That New Cheat"
    keys:
      - "key.mynewmod.something"
    actions:
      - threshold: 1
        commands:
          - "litebans:ban {player} 30d Cheating ({mod})"
```

## Building from source

```bash
mvn clean package
```

Output: `target/AntiMod-1.1.0.jar`

## Building via GitHub Actions

Every push to `main` builds and uploads the jar as a workflow artifact. Tag a release as `v1.1.0` (or similar) to have the jar attached to a GitHub release automatically.

## Notes and caveats

- **Join timing:** on Paper 1.21.4+ (including Folia forks) the join sweep waits for the client to report that it has loaded the world. Paper reports it after 3 seconds anyway if the client stays silent, and AntiMod gives up waiting after 10 seconds. Probing earlier doesn't work: a client still on the loading screen has no chunk to put the phantom signs in, so it drops them or answers with a blank sign. Older servers probe right after the join event.
- **Silence:** the sign editor should not visibly open, because the CLOSE_WINDOW packet arrives in the same batch, before the client draws the editor UI. In rare cases it *may* flash briefly. This is a client-side rendering limitation.
- **Bypasses:** any player with `antimod.bypass` is never probed. Give it sparingly.
- **False positives:** the fallback comparison is exact. If a mod happens to translate its key to literally `am_fb`, change `detection.fallback-marker` to something more unique.
- **Anti-cheat sensitivity:** because this uses raw packets and can trigger a rapid open/close of the sign editor, some clients with client-side anti-cheat or protocol validators may complain in their logs. Vanilla and standard modded clients handle it fine.
- **Mod adaptability:** if a mod author becomes aware of this detection and changes their translation keys, add the new keys to config. The mechanism itself keeps working.
- **Floodgate support:** if you have Geyser + Floodgate installed for Xbox/Bedrock players, AntiMod can detect mods on them too. Set `detection.floodgate-enabled: true` (default) to enable it, or `false` to skip Bedrock players. Requires Floodgate plugin to be installed on the server.
- **Folia support:** AntiMod is fully compatible with Folia (Paper's multi-threaded region scheduler). Detection tasks automatically use Folia's region scheduler when available, and fall back to Bukkit's standard scheduler on regular Paper servers.

## License

Do whatever you want with this. No warranty.
