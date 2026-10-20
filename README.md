# AntiMod

Silent client-mod detection for Paper 1.21.6, using the sign / translation-key exploit. **Fully compatible with Folia**.

Sends a phantom sign to the client during the join sequence, packed with translation keys for banned mods. The client either resolves the key (mod is installed) or falls back to the marker (mod is absent). The client's `UPDATE_SIGN` reply reveals which mods are present. The whole handshake runs during the loading screen, invisibly.

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
  join-delay-ticks: 0        # 0 = probe immediately, during the loading screen
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

When an entry fails for **5 different players** who verified every other mod in the same sweep, the entry is the problem, not the players. AntiMod comments it out in `spoof-mods.yml` with a note explaining why and reloads the list. The tallies live in `spoof-mod-misses.yml`, and `auto-disable-after` sets the count.

`/antimod channels <player>` shows what a client registered and which entries those channels match, which makes it easy to add a new mod. Settings and actions are under `spoof-detection` in `config.yml`.

### False-positive safeguards

- **Proxy channels:** Velocity registers its own plugins' channels on the backend for every player. For example, voicechat-velocity registers `voicechat:secret` and `voicechat:request_secret`, which would make vanilla players look like they run Simple Voice Chat. Those channels are excluded by `ignore-channels`. Any channel a vanilla-brand client carries is learned and ignored automatically (`learn-proxy-channels`). Vanilla-brand and Bedrock (Floodgate) players are never spoof-checked.
- **Old clients:** before 1.19.4, clients ignore the translation `fallback` and show the raw key for unknown keys. A raw-key answer is therefore never counted as proof of any mod, banned or spoof-check. It only counts as tampering for Java 1.19.4+ clients (version read through ViaVersion when present).
- **Majority rule:** hidden mods must outnumber verified ones. On top of that, by default `min-hidden-mods: 2`, so one odd mod build or one stray channel can't flag anyone.
- **Self-cleaning list:** entries that keep failing for players who verify everything else are disabled automatically (see above).
- **Keys that exist in every build:** each key in `spoof-mods.yml` is present in **every** sampled build of its mod (Fabric, Quilt, Forge and NeoForge, MC 1.16.5+, newest and oldest build per MC release). It isn't a vanilla key, no other mod ships it, and its value is non-empty and safe in every language.

**Limitation:** with their default settings, OpSec (AUTO whitelist) and ExploitPreventer both deliberately let the keys of channel-registering mods resolve. A default install therefore answers consistently and is **not** flagged. The check catches stricter setups, where keys are blocked for a mod whose channels are still visible.

## Mod scan (`/antimod mods <player>`)

`/antimod mods <player>` probes a player for every mod in `plugins/AntiMod/mod-catalog.yml`, plus the banned mods, and lists:

- **Detected by translation keys:** mods whose key resolved on the client.
- **Expected from channels:** the mods the player's plugin channels say they have, marked *verified* if their keys resolved, *keys hidden* if every key came back untranslated, *not in catalog* if there's no key for them, or *no answer*.
- **Channels from unlisted mods:** channel namespaces that no catalog entry accounts for.

The catalog is generated from the most downloaded client-side mods on Modrinth and the most downloaded mods on CurseForge, with one key per mod. Each key is present in every jar of its mod that was read, isn't a vanilla key, and isn't shipped by any other listed mod. A scan only reports: it never records violations or runs actions. Its signs go out slowly (`mod-scan.signs-per-tick`, 5 by default, so a full scan takes about 10 seconds). Each sign is answered by the client, and ViaVersion or Paper kick clients that send too many packets a second. The signs sit in a small grid of columns above the player.

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
| `/antimod check <player>`           | Probe a player right now                 |
| `/antimod violations <player>`      | Show a player's per-mod violation counts |
| `/antimod clear <player> [mod]`     | Clear violations (all mods, or one)      |
| `/antimod info`                     | List configured mods                     |
| `/antimod channels <player>`        | Show a player's registered plugin channels |
| `/antimod mods <player>`            | Scan a player for known mods (report only) |

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

- **Silence:** the sign editor should never visibly open during a join because the CLOSE_WINDOW packet arrives before the client draws the editor UI. If you see it briefly flash for players who join mid-tick, increase `detection.join-delay-ticks` — but for on-demand `/antimod check` runs against fully-loaded players it *may* briefly flash a sign editor. This is a client-side rendering limitation.
- **Bypasses:** any player with `antimod.bypass` is never probed. Give it sparingly.
- **False positives:** the fallback comparison is exact. If a mod happens to translate its key to literally `am_fb`, change `detection.fallback-marker` to something more unique.
- **Anti-cheat sensitivity:** because this uses raw packets and can trigger a rapid open/close of the sign editor, some clients with client-side anti-cheat or protocol validators may complain in their logs. Vanilla and standard modded clients handle it fine.
- **Mod adaptability:** if a mod author becomes aware of this detection and changes their translation keys, add the new keys to config. The mechanism itself keeps working.
- **Floodgate support:** if you have Geyser + Floodgate installed for Xbox/Bedrock players, AntiMod can detect mods on them too. Set `detection.floodgate-enabled: true` (default) to enable it, or `false` to skip Bedrock players. Requires Floodgate plugin to be installed on the server.
- **Folia support:** AntiMod is fully compatible with Folia (Paper's multi-threaded region scheduler). Detection tasks automatically use Folia's region scheduler when available, and fall back to Bukkit's standard scheduler on regular Paper servers.

## License

Do whatever you want with this. No warranty.
