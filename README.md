# AntiMod

Silent client-mod detection for Paper 1.21.6, using the sign / translation-key exploit.

Sends a phantom sign to the client during the join sequence, packed with translation keys for banned mods. The client either resolves the key (mod is installed) or falls back to the marker (mod is absent). The client's `UPDATE_SIGN` reply reveals which mods are present. The whole handshake runs during the loading screen, invisibly.

Inspired by the technique used on DonutSMP.

## Requirements

- **Paper 1.21.6** (also works on later 1.21.x; other versions untested)
- **ProtocolLib 5.4.0+**  
  For Minecraft 1.21.6+ you may need the latest dev build from https://ci.dmulloy2.net/job/ProtocolLib/ if 5.4.0 stable doesn't yet support your specific 1.21.x point release.
- **Java 21**

## Installation

1. Drop `AntiMod-1.0.0.jar` into `plugins/`.
2. Ensure `ProtocolLib` is also in `plugins/`.
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
  join-delay-ticks: 40       # wait 2s after join for client to settle
  periodic:
    enabled: false           # re-probe online players periodically
    interval: 600            # ...every 10 minutes
  sign-position:             # phantom sign position (never actually placed)
    x: 159575
    y: 89
    z: 58081
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

Output: `target/AntiMod-1.0.0.jar`

## Building via GitHub Actions

Every push to `main` builds and uploads the jar as a workflow artifact. Tag a release as `v1.0.0` (or similar) to have the jar attached to a GitHub release automatically.

## Notes and caveats

- **Silence:** the sign editor should never visibly open during a join because the CLOSE_WINDOW packet arrives before the client draws the editor UI. If you see it briefly flash for players who join mid-tick, increase `detection.join-delay-ticks` — but for on-demand `/antimod check` runs against fully-loaded players it *may* briefly flash a sign editor. This is a client-side rendering limitation.
- **Bypasses:** any player with `antimod.bypass` is never probed. Give it sparingly.
- **False positives:** the fallback comparison is exact. If a mod happens to translate its key to literally `am_fb`, change `detection.fallback-marker` to something more unique.
- **Anti-cheat sensitivity:** because this uses raw packets and can trigger a rapid open/close of the sign editor, some clients with client-side anti-cheat or protocol validators may complain in their logs. Vanilla and standard modded clients handle it fine.
- **Mod adaptability:** if a mod author becomes aware of this detection and changes their translation keys, add the new keys to config. The mechanism itself keeps working.

## License

Do whatever you want with this. No warranty.
