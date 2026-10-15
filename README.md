# Debug (Anti Vanish for Meteor Client)

Meteor addon with one module: **Anti Vanish**, ported from the Anubis client.

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

Staff list: default is the DonutSMP staff list from Anubis. Edit it in module settings (Staff -> staff-names),
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
