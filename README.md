# X3 Timer

A dual-mode timer for the RayNeo X3 Pro — a **Pomodoro** focus mode and a
synthwave **Athletic** mode (HIIT/Tabata · EMOM · AMRAP) — built as a tiny,
glanceable AR HUD. The core widget occupies **< 20% of the screen**; everything
else is black (transparent on the waveguide), so the timer floats in your
peripheral vision and never blocks your view.

Same proven, self-contained stack as [X3 Snake](https://github.com/tropicalstream/x3snake):
custom Canvas rendering inside a dual-draw `BinocularSbsLayout`, synthesized audio,
**no external dependencies**.

## Controls (right temple pad)

| Gesture | Action |
|---|---|
| **Tap** | Start / pause |
| **Double-tap** | Reset the current program |
| **Swipe ← / →** | Previous / next program (Pomodoro → HIIT → EMOM → AMRAP) |
| **Swipe ↑** | Pomodoro: **extend focus +10 min** (flow protection) · HIIT/EMOM: skip interval · AMRAP: +1 lap |
| **Swipe ↓** | Pomodoro: cycle bound task · Athletic: mute |
| **Left-pad tap** | Universal mute toggle |

### Accidental-switch guard

While a timer is **running or paused**, a left/right swipe doesn't switch modes
immediately — it shows a **"SWITCH TO … — SWIPE AGAIN"** banner with a **5-second
countdown**. Swipe again within the window to confirm; otherwise it auto-cancels
and your session keeps running. (When idle or finished, swipes switch instantly.)

## Pomodoro mode (deep-work focus)

- **Auto-minimize for passthrough** — while focus is running, after **5 seconds of no
  interaction** the HUD **shrinks to a small chip in the upper-left corner** (just
  phase + time), clearing your view for reading or coding. **Any right-arm interaction
  expands it back** to full size; it re-minimizes after another 5s idle. Phase changes
  (focus→break) briefly pop it back so you notice. *(Athletic modes never minimize — an
  athlete mid-workout needs the big readout.)*
- **Flow protection** — swipe up at any time to seamlessly add 10 minutes without resetting your streak.
- **Direct task binding** — each focus block is bound to a task from a local JSON list; finishing a focus auto-logs a "tomato" against it (no manual logging).
- **Final-minute cue** — in the last 60 seconds the panel shifts to a calm **blue** and the screen edge gently pulses, so you feel the break coming without a hard alarm.
- **Velocity analytics** — completed pomodoros are logged per day (persisted, survives reboot); the idle screen shows a 7-day sparkline, and the panel shows today's count + a persistent **day-streak** (consecutive days with ≥1 pomodoro).

## Athletic mode (synthwave)

- **Modalities** — HIIT/Tabata (20s/10s × 8), EMOM (sharp beep every minute × 10), AMRAP (count-up + lap counter).
- **Glanceable color-coding** — vibrant green = WORK, crimson = REST, amber = GET READY; the whole **screen edge pulses** the status color so it's legible from the corner of your eye, and **flashes amber in the final 5 seconds**.
- **Rewarding** — particle bursts on each interval/round, a fanfare + gold burst on completion.
- **Sharp audio** — 3-2-1 countdown beeps, a GO tone, per-minute EMOM beeps, round/workout cues.

## Build & install

```bash
cd /Users/me/Projects/x3timer
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell am start -n com.tropicalstream.x3timer.debug/com.tropicalstream.x3timer.MainActivity
```

A prebuilt `X3Timer.apk` is attached to the latest [release](https://github.com/tropicalstream/x3timer/releases).

## Architecture

Pure Android SDK, custom Canvas, dual-draw binocular — no libVLC/SurfaceView, nothing to crash on.

| Area | File |
|---|---|
| Binocular SBS (dual-draw, live width/2) | `ui/BinocularSbsLayout.kt` |
| Temple trackpad → tap/double-tap/swipes | `input/TrackpadGestureEngine.kt` |
| Shared countdown/interval state machine | `timer/TimerEngine.kt` · `timer/Program.kt` |
| Pomodoro task binding + tomato log (JSON) | `timer/TaskStore.kt` |
| Per-day velocity analytics + day-streak | `timer/StatsStore.kt` |
| Synthesized tones + noise (no assets) | `sound/SoundEngine.kt` |
| HUD render, themes, minimize, edge pulse | `render/TimerView.kt` |
| Density, loop, input + gesture wiring | `MainActivity.kt` |

X3 specifics: density set once via `attachBaseContext` + `createConfigurationContext(DENSITY_MEDIUM)`
(never mutate `DisplayMetrics.widthPixels`); black canvas = transparent on the waveguide; neon glow
via layered translucent draws to stay hardware-accelerated.
