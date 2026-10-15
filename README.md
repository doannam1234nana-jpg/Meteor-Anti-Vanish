# Debug (Anti Vanish + Staff List for Meteor Client)

Meteor addon with two modules ported from the Anubis client: **Anti Vanish** and **Staff List**.

Minecraft 1.21.11 · Fabric · Meteor Client

## Detection
- **tab-watch** - player hidden/removed from TAB without a leave message
- **spectator-watch** - player switched to spectator
- **command-probe** - tab-completes `/msg`; players online but not in TAB are flagged
- **invisible-players** - invisible player entities nearby
- **sound-detection** - chest/door/button/lever sounds with no visible cause
- **particle-detection** - crit/smoke/block particles with no visible cause
- **critical** - several different signals add up to a CRITICAL alert (+ chime)
- **HUD** - `Anti Vanish` element (HUD editor) listing live detections

## Staff List (module `staff-list-plus` + HUD element `staff-list-plus`)
- Lists staff currently in TAB, with rank (Owner/Admin/Mod/...) in chat and **VANISHED** / **SPECTATOR** status on the HUD
- **WATCHING YOU** - Anubis-style sequence: staff face flashes on screen (1.5s), then big red title + guardian sound when a staff member appears near you, or a hidden staff
  member switches gamemode in your region (same trigger as Anubis). `Staff +N` in the subtitle if several at once
- HUD row shows `WATCHING YOU <distance>m` while the body is nearby, otherwise `WATCHING YOU <m:ss>` for how long
- Chat: staff joined / vanished / visible again / left
- Settings: `chat-alerts`, `watching-alert`, `title-alert`, `sound-alert`, `staff-names`
- Add the HUD: open the HUD editor and add `Staff List Plus` from the Debug group
- Anti Vanish also shows the WATCHING YOU title on a CRITICAL alert (`title-alert` in its Critical group)

After the title ends (4.5s) a gold "Goodluck Anubis User, Be Careful" note fades in and out, like Anubis.
The face is a 2D approximation (squash-to-spin) of the Anubis 3D cube head. Not ported: the Donut region/shard map.

Staff list (both modules): default is the DonutSMP staff list from Anubis. Edit it in module settings (Staff -> staff-names),
or turn `staff-only` off to track everyone (noisy).

## Build
GitHub (works from a phone): create a repo, upload this folder's contents, open the Actions tab.
The workflow installs Gradle itself (there is no gradle wrapper in this zip). The jar is under Artifacts.

PC: install JDK 21 and Gradle 9.x, then run `gradle build`. Jar: `build/libs/`.

If the build fails, check `gradle.properties` (yarn_mappings, loader_version, meteor_version) against your
Meteor version, and send the error log.

## License / credit
GPL-3.0 (see LICENSE). Detection logic is derived from the Anubis client by 4ldenz (GPL-3.0).
The DonutSMP staff names come from the same source. Keep this credit if you redistribute.
