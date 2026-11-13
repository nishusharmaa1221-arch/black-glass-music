#!/usr/bin/env bash
# Musify REAL screenshot capture — runs INSIDE the GitHub Actions emulator.
# Drives the actual v1.2.0 app: login (test account) -> setup wizard ->
# library -> play the pushed song -> profile -> audio lab.
# Env in: APK_PATH, SS_EMAIL, SS_PASS. Shots land in ./shots/
set -u
PKG=com.mochyy.mochymusic
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/shots"
mkdir -p "$OUT"
UI="$OUT/ui-current.xml"
SONG_LOCAL="$ROOT/.github/test-assets/nuclear-freestyle.mp3"
SONG_REMOTE="/sdcard/Music/Sheru Bhai - Nuclear Bomb Freestyle.mp3"
SONG_TITLE="Nuclear Bomb Freestyle"
STEP=0

log() { echo "==[capture] $*"; }

shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; log "shot $1"; }

dump() {
  for _ in 1 2 3; do
    if adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; then
      adb pull /sdcard/ui.xml "$UI" >/dev/null 2>&1 && return 0
    fi
    sleep 1
  done
  log "WARN: uiautomator dump failed"
  return 1
}

texts() { python3 "$ROOT/.github/scripts/uitap.py" texts "$UI" 2>/dev/null; }

tap_find() { # tap_find <needle> [extra uitap args...]
  local needle="$1"; shift
  local coords
  coords=$(python3 "$ROOT/.github/scripts/uitap.py" find "$UI" "$needle" "$@" 2>/dev/null) || return 1
  log "tap '$needle' @ $coords"
  adb shell input tap $coords
  return 0
}

has() { python3 "$ROOT/.github/scripts/uitap.py" find "$UI" "$1" >/dev/null 2>&1; }
has_desc() { python3 "$ROOT/.github/scripts/uitap.py" find "$UI" "$1" --desc >/dev/null 2>&1; }
has_desc_exact() { python3 "$ROOT/.github/scripts/uitap.py" find "$UI" "$1" --desc --exact >/dev/null 2>&1; }

tap_field() { # tap_field <index> : tap the Nth EditText on screen
  local coords
  coords=$(python3 "$ROOT/.github/scripts/uitap.py" find "$UI" "" --class "EditText" --index "$1" 2>/dev/null) || return 1
  adb shell input tap $coords
  return 0
}

debug_snap() {
  STEP=$((STEP+1))
  shot "dbg-$(printf %02d $STEP)"
  dump || true
  texts > "$OUT/dbg-$(printf %02d $STEP)-texts.txt" 2>/dev/null || true
}

wait_for_text() { # wait_for_text <needle> <seconds>
  local end=$((SECONDS + $2))
  while [ $SECONDS -lt $end ]; do
    dump || true
    if has "$1"; then return 0; fi
    sleep 2
  done
  return 1
}

# ---------------------------------------------------------------- setup
log "waiting for device"
adb wait-for-device
for i in $(seq 1 60); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 3
done
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell settings put system screen_off_timeout 600000
# the full player is immersive; pre-confirm it so SystemUI never shows the
# blue "Viewing full screen" hint card over the player (run 9 flaw)
adb shell settings put secure immersive_mode_confirmations "$PKG" 2>/dev/null || true

log "installing $APK_PATH"
adb install -g -r "$APK_PATH" || { log "INSTALL FAILED"; exit 1; }

adb shell pm grant $PKG android.permission.READ_MEDIA_AUDIO 2>/dev/null || true
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true

log "pushing song BEFORE first launch"
adb shell mkdir -p /sdcard/Music
adb push "$SONG_LOCAL" "$SONG_REMOTE"
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
  -d "file:///sdcard/Music/Sheru%20Bhai%20-%20Nuclear%20Bomb%20Freestyle.mp3" >/dev/null 2>&1 || true
adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1 || true
sleep 2
adb shell content query --uri content://media/external/audio/media --projection title 2>/dev/null | head -5 || true

log "launching app"
adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1 \
  || adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 8
debug_snap

# ---------------------------------------------------------------- login
shot login
dump || true
if has "Email"; then
  log "login screen found"
  tap_field 0 && adb shell input text "$SS_EMAIL"
  sleep 1
  tap_field 1 && adb shell input text "$SS_PASS"
  sleep 1
  adb shell input keyevent 4   # hide keyboard
  sleep 1
  dump || true
  shot login-filled
  if tap_find "Log In" --exact; then log "tapped Log In"; else tap_find "Log in"; fi
  sleep 8
else
  log "no login screen at first look"
fi
debug_snap

# ------------------------------------------------------- setup wizard
in_main_ui() {
  # EXACT descs only: the setup profile page contains "Add a profile picture",
  # which substring-matches "Profile" and faked a main-UI hit in run 3.
  has "Try something new" || has_desc_exact "Your Library" || has_desc_exact "Profile"
}
for i in $(seq 1 16); do
  dump || true
  if has "what's your username"; then
    log "profile page: setting username"
    shot wizard-profile
    tap_field 0 && adb shell input text "Nirbhay"
    sleep 1
    adb shell input keyevent 4
    sleep 1
    dump || true
    tap_find "Next" --exact || true
    sleep 3
  elif in_main_ui; then
    log "main UI reached (wizard done)"
    break
  elif has "Skip"; then
    tap_find "Skip" --exact || true
    sleep 3
  elif has "Next"; then
    tap_find "Next" --exact || tap_find "Next" || true
    sleep 3
  elif has "next"; then
    tap_find "next" --exact || true
    sleep 3
  elif has "Get Started" || has "Get started"; then
    tap_find "Get started" || tap_find "Get Started" || true
    sleep 3
  elif has "Done"; then
    tap_find "Done" --exact || true
    sleep 3
  else
    log "wizard loop: unknown screen, snapshotting"
    debug_snap
    sleep 3
  fi
done
debug_snap
shot home-first

ensure_app_fg() {
  local focus
  focus=$(adb shell dumpsys window 2>/dev/null | grep -m1 "mCurrentFocus" || true)
  case "$focus" in
    *"$PKG"*) : ;;
    *)
      log "app not in foreground ($focus) - relaunching"
      adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1
      sleep 5
      ;;
  esac
}

goto_library() {
  ensure_app_fg
  dump || true
  tap_find "Your Library" --desc || tap_find "Library" || adb shell input tap 654 2232
  sleep 3
}

# ------------------------------------------------------- library & play
goto_library
dump || true
# Local device songs live under the "Local" filter chip, not in "All",
# and Musify only lists them after an explicit import (Add music sheet).
if ! has "$SONG_TITLE"; then
  tap_find "Local" --exact || true
  sleep 3
  dump || true
fi
shot library-local-empty
if ! has "$SONG_TITLE" && has "Import your local music here"; then
  log "opening Add music sheet (device scan list)"
  tap_find "Import your local music here" || true
  if wait_for_text "$SONG_TITLE" 25; then
    sleep 2   # let the sheet finish sliding up
    dump || true
    shot add-music-sheet
    # NOTE: the sheet opens with every found song PRE-SELECTED (run 5 proof:
    # header read "Select none", button "Add 1 song"). Tapping the row would
    # DESELECT it. Just press the Add button; fall back to Select all once.
    if tap_find "Add 1"; then
      log "import started (pre-selected)"
    else
      tap_find "Select all" || true
      sleep 1
      dump || true
      tap_find "Add 1" || true
    fi
    # the import progress sheet opens and STAYS open (100%, "Create playlist")
    # — it lists the song title, which would fool the play phase into tapping
    # a dead row (run 6). Wait for it, then dismiss it by dragging it down.
    for w in $(seq 1 10); do
      dump || true
      has "Import progress" && break
      sleep 2
    done
    shot after-import
    for d in 1 2 3; do
      dump || true
      has "Import progress" || break
      adb shell input swipe 540 1680 540 2350 300
      sleep 1
      dump || true
      if has "Import progress"; then adb shell input keyevent 4; sleep 1; fi
    done
    dump || true
    if has "Import progress"; then log "WARNING: progress sheet still open"; debug_snap; fi
  else
    log "WARNING: song not listed in Add music sheet"
    debug_snap
  fi
fi
# after import, the song should surface in the Local tab / Songs shelf card
dump || true
if ! has "$SONG_TITLE"; then
  goto_library
  dump || true
  tap_find "Local" --exact || true
  sleep 2
  dump || true
fi
shot library
if ! has "$SONG_TITLE"; then
  tap_find "Songs" || true
  sleep 3
  dump || true
  shot library-songs
fi

if has "$SONG_TITLE" && ! has "Import progress"; then
  log "song visible in library — tapping to play"
  tap_find "$SONG_TITLE" || true
  sleep 2
  shot after-tap-song
  # Expand the flagship player. Runs 7-8 never actually attempted this: the
  # old detector keyed on a "Your Library" desc that this screen doesn't
  # have, so it reported "expanded" instantly. Fixed coords below come from
  # the run 7/8 screenshots (mini bar y~2050, title x~490, artwork x~140).
  # Expanded test on the CURRENT dump: the full player is the only play-phase
  # surface with a lyrics card. (Run 11 proved the seekbar clock is NOT a
  # text node — canvas-drawn — and the list stays in the tree behind the
  # sheet, so the old time-node/"Local" tests never fired and wasted ~10s.)
  player_expanded() { has "No lyrics" || has "Lyrics"; }
  # Emulator playback runs FASTER than realtime (~1.4x, no audio device):
  # expand FAST and shoot immediately. Run 11's expand-a proved attempt A
  # (tap on the mini-player body) opens the player at once (~0:07).
  # A: tap the mini-player body
  adb shell input tap 490 2043
  sleep 2
  dump || true
  shot expand-a
  if ! player_expanded; then
    # B: upward drag on the mini bar ("open with your finger")
    adb shell input swipe 540 2050 540 900 450
    sleep 2
    dump || true
    shot expand-b
  fi
  if ! player_expanded; then
    # C: tap the mini-player artwork
    adb shell input tap 140 2050
    sleep 2
    dump || true
    shot expand-c
  fi
  if ! player_expanded; then
    # D: tap the playing row again
    tap_find "$SONG_TITLE" || true
    sleep 2
    dump || true
    shot expand-d
  fi
  shot player-open
  # If the immersive hint still appeared despite the pre-confirm, dismiss it
  if has "Got it"; then tap_find "Got it" --exact || true; sleep 1; fi
  shot now-playing-1
  adb shell dumpsys media_session 2>/dev/null | grep -a "PlaybackState" | head -3 || true
  sleep 3
  shot now-playing-2
else
  log "WARNING: song not found in library UI (or progress sheet stuck)"
  debug_snap
fi

# ------------------------------------------------------------- profile
ensure_app_fg
adb shell input keyevent 4   # collapse full player (once only!)
sleep 2
ensure_app_fg
dump || true
# Bottom-nav 4th tab (M icon) opens the Profile screen; top avatar works too
if ! tap_find "Settings" --desc; then
  if ! tap_find "Profile" --desc; then
    adb shell input tap 884 2232
  fi
fi
sleep 3
dump || true
shot profile
texts > "$OUT/profile-texts.txt" 2>/dev/null || true

# ------------------------------------------------------------ audio lab
ensure_app_fg
open_audio_lab() {
  dump || true
  if has "Audio Lab"; then tap_find "Audio Lab" && return 0; fi
  if has "Equalizer"; then tap_find "Equalizer" && return 0; fi
  return 1
}
if ! open_audio_lab; then
  # Profile screen has a Settings section with an "Open settings" button
  for s in 1 2 3; do
    dump || true
    if has "Open settings"; then tap_find "Open settings" && break; fi
    adb shell input swipe 540 1800 540 800 400
    sleep 1
  done
  sleep 3
  if ! open_audio_lab; then
    dump || true
    if has "Search settings"; then
      tap_find "Search settings" || tap_field 0 || true
      sleep 1
      adb shell input text "Equalizer"
      sleep 2
      dump || true
      shot settings-search-eq
      tap_find "Equalizer" || tap_find "Audio Lab" || true
    else
      for s in 1 2 3 4; do
        adb shell input swipe 540 1800 540 700 400
        sleep 1
        if open_audio_lab; then break; fi
      done
    fi
  fi
fi
sleep 3
dump || true
shot audiolab
texts > "$OUT/audiolab-texts.txt" 2>/dev/null || true
debug_snap

log "capture script finished"
ls -la "$OUT"
