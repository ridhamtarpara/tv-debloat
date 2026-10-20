# Mi TV (MiTV-AXSO2) debloat + Calm launcher

TV: Xiaomi MiTV-AXSO2, Android 11, 192.168.1.3:5555 (ADB over Wi-Fi). Done 2026-10-19.

## Undo everything
```bash
scripts/restore.sh                     # re-enable every package, reset settings, Home back to Google TV
scripts/restore.sh --remove-launcher   # same, and uninstall Calm
scripts/restore.sh --dry-run           # print what it would do
scripts/restore.sh --from-backup       # safety net: re-enable anything disabled since the backup
```
If the TV's IP changes, pass it first: `scripts/restore.sh 192.168.1.42`.
If ADB is refused, accept "Allow USB debugging" on the TV and rerun.

Nothing was uninstalled. Every change is a `pm disable-user`, a `settings put` or the Home setting,
and each one is recorded in `changes.log`, which the restore script reads in reverse.

## What changed
| Disabled | Why |
|---|---|
| com.miui.tv.analytics, com.xiaomi.statistic | Xiaomi tracking |
| com.xm.webcontent, com.mitv.overseaservice | Xiaomi ad/content push |
| android.autoinstalls.config.xioami.dangal | Silently installs promoted apps |
| com.google.android.tvrecommendations, com.google.android.feedback | Sponsored rows, crash reporting |
| com.mitv.tvhome.atv, com.mitv.tvhome.michannel | PatchWall (160 MB), replaced by Calm |
| com.mitv.milinkservice, com.xiaomi.mitv.smartshare, com.xiaomi.wfdsinkhelperservice | Mi Cast / Miracast (not used) |
| com.google.android.play.games, com.google.android.youtube.tvmusic | Not used |
| com.google.android.tvlauncher | Google TV home with the "Sponsored" hero. It has to be off for any other Home to work |

## Remote buttons
| Button | Goes to |
|---|---|
| Home (circle) | Calm |
| Mi | Mi TV+ (or Calm if Mi TV+ is ever disabled), via Calm's "Calm remote buttons" accessibility service |
| Netflix | Netflix, via Xiaomi's `partnercustomizer` |
| Prime Video | **YouTube**, via Calm's "Calm remote buttons" accessibility service |
| Mic | Google Assistant |

- `partnercustomizer` was re-enabled after testing. It's the receiver for the hotkeys, not ad junk.
- On Indian firmware it sends the Mi button to PatchWall, or to Live TV on HDMI 1 when PatchWall is off.
  Calm's accessibility service catches only the keys in its `REMAP` table first (Mi = `BUTTON_5` → Mi TV+,
  Prime = `BUTTON_4` → YouTube). To remap a button, edit that table in `launcher/src/com/calm/launcher/MiKeyService.java`.
  It sees no other keys, no screen content and no UI events.
- Enabled with `secure/enabled_accessibility_services` and `secure/accessibility_enabled=1`. Both are logged,
  so `restore.sh` reverts them.
- If Calm is ever **force-stopped** (Settings › Apps › Calm › Force stop, or a "memory cleaner"), Android switches
  the service off and the Mi button goes back to Live TV/HDMI. Re-enable it with:
  `adb -s 192.168.1.3:5555 install -r launcher/calm.apk`, then
  `adb -s 192.168.1.3:5555 shell settings put secure enabled_accessibility_services com.calm.launcher/com.calm.launcher.MiKeyService`.
  Normal memory pressure doesn't cause this.

### Press-and-hold volume
Key filtering (needed for the Mi/Prime remaps) makes Android handle volume keys inside the accessibility layer,
which drops the auto-repeat it normally adds while a button is held, so a held button stepped only once. The
remote itself sends just one press and one release per hold. Calm restores the repeat in `VolumeHoldWatcher.java`:
- It follows the window manager's own press/release log lines (`interceptKeyTi keyCode=24/25 down=true/false`),
  which needs `READ_LOGS` (granted over ADB, logged, and revoked by `restore.sh`).
- After 400 ms of holding it changes the volume by 2 levels every 100 ms (about 0 → 100 in 5 s) and stops on release.
  The TV's audio stack applies only about 12 changes a second, so anything faster queued up and kept changing
  the volume after release.
- Each volume change makes the audio stack log dozens of lines into the same `main` log buffer. At the stock
  256 KB a hold wrapped it in under a second and the release line got lost, so:
  - `persist.log.tag.APM_AudioPolicyEngine=W` quiets the noisiest audio tag (about 5x less log per step).
  - Calm enlarges the `main` buffer to 2 MB every time it starts (ADB can't make that survive a reboot).
  Both are logged and reverted by `restore.sh`.
- Single presses and Xiaomi's double-press-to-mute are untouched.

Settings: the three animation scales went from 1.0 to 0.5.
Kept on request: Mi TV+, Xiaomi updates, Google Assistant, Chromecast, Google TV/Play Movies.

Note: `overseaservice` is flagged PERSISTENT by Xiaomi, so Android still starts it at boot (about 6 MB),
but its components are disabled. Stopping it entirely would need
`pm uninstall --user 0`, which was deliberately not done.

## Calm launcher (`launcher/`)
- Dock: Netflix, YouTube, Prime Video, JioHotstar, then All Apps, Inputs (HDMI/AV) and Settings.
- Long-press OK (or Menu) on a dock app to move or remove it. Long-press in All Apps to add an app to the dock.
- Wallpapers: 4 new Wikimedia Commons featured nature photos a day (mountains, lakes, forests, waterfalls,
  beaches, coasts). They rotate every 5 minutes, with a credit line as the licences require. 15 are kept, and
  3 bundled photos cover offline use. The only network hosts are commons.wikimedia.org and *.wikimedia.org.
- Rebuild: `launcher/build.sh` (needs Homebrew `android-commandlinetools` + `openjdk@17`), then
  `adb -s 192.168.1.3:5555 install -r launcher/calm.apk`. `calm.keystore` must be kept for updates to install.

## Files
- `scripts/tv.sh`: helper that logs every change (shot / disable / enable / setting / home / health)
- `scripts/restore.sh`: undo script (reads `changes.log`)
- `launcher/`: Calm source and `build.sh`
- `launcher/assets/wallpapers/`: 3 bundled photos from Wikimedia Commons, each with its credit and licence
  in the matching `.txt` file (CC BY-SA 3.0, CC BY 2.0, CC BY-SA 4.0)

Kept **locally only**, not in this repo: `backup/` (device state and app APKs), `screens/` (verification
screenshots), `changes.log` (this TV's change history, created by `tv.sh`), and `launcher/calm.keystore`.
