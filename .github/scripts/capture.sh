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
    # no wizard controls visible — maybe we've reached the main UI
    if has "Library" || has "$SONG_TITLE"; then
      log "looks like main UI reached"
      break
    fi
    log "wizard loop: unknown screen, snapshotting"
    debug_snap
    sleep 3
  fi
done
debug_snap
shot home-first

# ------------------------------------------------------- library & play
# Try the Library tab (bottom nav text or desc)
dump || true
tap_find "Library" || tap_find "Library" --desc || true
sleep 3
dump || true
if ! has "$SONG_TITLE"; then
  tap_find "Songs" || true
  sleep 2
  dump || true
fi
shot library
# scroll a bit if the song is not visible
if ! has "$SONG_TITLE"; then
  adb shell input swipe 540 1700 540 800 400
  sleep 2
  dump || true
  shot library-scrolled
fi

if has "$SONG_TITLE"; then
  log "song visible in library — tapping to play"
  tap_find "$SONG_TITLE" || true
  sleep 3
  dump || true
  shot after-tap-song
  adb shell dumpsys media_session 2>/dev/null | grep -E "state=PlaybackState|position" | head -4 || true
  # open full player: tap the mini-player (song title in the bar, 2nd match)
  tap_find "$SONG_TITLE" --index 1 || tap_find "$SONG_TITLE" || true
  sleep 3
  dump || true
  shot player-open
  sleep 4
  shot now-playing-1
  adb shell dumpsys media_session 2>/dev/null | grep -E "state=PlaybackState" | head -3 || true
  sleep 5
  shot now-playing-2
else
  log "WARNING: song not found in library UI"
  debug_snap
fi

# ------------------------------------------------------------- profile
adb shell input keyevent 4   # close player if open
sleep 2
adb shell input keyevent 4
sleep 2
dump || true
tap_find "Settings" || tap_find "Settings" --desc || true
sleep 3
dump || true
shot profile

# ------------------------------------------------------------ audio lab
if has "Audio Lab"; then
  tap_find "Audio Lab" || true
else
  # profile screen may need a scroll, or a settings-list entry point
  adb shell input swipe 540 1800 540 700 400
  sleep 2
  dump || true
  if has "Audio Lab"; then
    tap_find "Audio Lab" || true
  elif has "Equalizer"; then
    tap_find "Equalizer" || true
  elif has "Settings"; then
    tap_find "Settings" || true
    sleep 2
    dump || true
    tap_find "Audio Lab" || tap_find "Equalizer" || true
  fi
fi
sleep 3
dump || true
shot audiolab
debug_snap

log "capture script finished"
ls -la "$OUT"
