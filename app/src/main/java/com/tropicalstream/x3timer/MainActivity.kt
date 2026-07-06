package com.tropicalstream.x3timer

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.tropicalstream.x3timer.fx.Particles
import com.tropicalstream.x3timer.input.TrackpadGestureEngine
import com.tropicalstream.x3timer.render.TimerView
import com.tropicalstream.x3timer.sound.SoundEngine
import com.tropicalstream.x3timer.timer.PhaseKind
import com.tropicalstream.x3timer.timer.ProgramType
import com.tropicalstream.x3timer.timer.StatsStore
import com.tropicalstream.x3timer.timer.TaskStore
import com.tropicalstream.x3timer.timer.TimerEngine
import com.tropicalstream.x3timer.ui.BinocularSbsLayout

class MainActivity : Activity() {

    private val engine = TimerEngine()
    private val particles = Particles()
    private val gestures = TrackpadGestureEngine()
    private val sound = SoundEngine()
    private lateinit var tasks: TaskStore
    private lateinit var stats: StatsStore
    private lateinit var view: TimerView

    private var running = false

    // Mode-switch confirmation guard (only while a session is in progress).
    private var pendingDir = 0
    private var pendingUntil = 0L

    // Pomodoro auto-minimize: shrink to the corner after idle, expand on interaction.
    private var lastInteractionMs = 0L

    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration).apply {
            densityDpi = DisplayMetrics.DENSITY_MEDIUM
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureImmersive()

        tasks = TaskStore(this)
        stats = StatsStore(this)
        view = TimerView(this, engine, particles, tasks, stats)
        setContentView(BinocularSbsLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view)
        })

        wireEngineCues()
        wireGestures()
    }

    private fun wireEngineCues() {
        engine.onPhaseEnter = { ph ->
            // Pop the HUD back to full size briefly when a phase changes, so a
            // focus→break transition is noticeable before it re-minimizes.
            lastInteractionMs = SystemClock.uptimeMillis()
            when (ph.kind) {
                PhaseKind.WORK -> { sound.go(); view.rewardWork() }
                PhaseKind.EMOM_ROUND -> { sound.intervalBeep(); view.rewardRound() }
                PhaseKind.REST -> sound.restBeep()
                PhaseKind.AMRAP -> sound.go()
                PhaseKind.FOCUS -> sound.focusChime()
                PhaseKind.BREAK, PhaseKind.LONG_BREAK -> { sound.stopNoise(); sound.breakChime() }
                else -> {}
            }
        }
        engine.onCountdownBeep = { sec -> sound.countBeep(sec) }
        engine.onFocusLogged = {
            stats.logPomodoro()
            tasks.logTomato()
        }
        engine.onDone = {
            sound.stopNoise()
            sound.workoutDone()
            view.rewardDone()
        }
    }

    private fun wireGestures() {
        gestures.onTap = { engine.toggleStartPause(SystemClock.uptimeMillis()) }
        gestures.onDoubleTap = { engine.reset(); sound.stopNoise() }
        gestures.onSwipeHorizontal = { dir ->
            val now = SystemClock.uptimeMillis()
            val inProgress = engine.state == TimerEngine.State.RUNNING ||
                engine.state == TimerEngine.State.PAUSED
            when {
                // Nothing to lose when idle/finished — switch immediately.
                !inProgress -> engine.cycleProgram(dir)
                // Second swipe within the window → confirm (latest direction wins).
                pendingDir != 0 && now < pendingUntil -> {
                    pendingDir = 0
                    engine.cycleProgram(dir)
                    sound.stopNoise()
                }
                // First swipe during a session → arm the 5s confirmation.
                else -> {
                    pendingDir = dir
                    pendingUntil = now + CONFIRM_WINDOW_MS
                    sound.restBeep()
                }
            }
        }
        gestures.onSwipeVertical = { dir ->
            if (dir < 0) {
                engine.actionUp(SystemClock.uptimeMillis())          // up = extend / skip / lap
            } else {
                if (engine.program == ProgramType.POMODORO) {
                    tasks.cycle()                                    // down = cycle bound task
                } else {
                    sound.enabled = !sound.enabled                   // down = mute (athletic)
                    if (!sound.enabled) sound.stopNoise()
                }
            }
        }
        gestures.onLeftTap = {
            sound.enabled = !sound.enabled                           // left pad = universal mute
            if (!sound.enabled) sound.stopNoise()
        }
    }

    private fun configureImmersive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.decorView.setBackgroundColor(Color.BLACK)
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            // Expire the mode-switch confirmation (timeout, or session ended).
            if (pendingDir != 0 && (now >= pendingUntil ||
                    (engine.state != TimerEngine.State.RUNNING && engine.state != TimerEngine.State.PAUSED))
            ) {
                pendingDir = 0
            }
            val frac = if (pendingDir != 0)
                ((pendingUntil - now).toFloat() / CONFIRM_WINDOW_MS).coerceIn(0f, 1f) else 0f
            view.setSwitchPrompt(pendingDir, frac)

            // Auto-minimize only during a RUNNING Pomodoro after 5s of no interaction.
            val minimize = engine.program == ProgramType.POMODORO &&
                engine.state == TimerEngine.State.RUNNING &&
                now - lastInteractionMs > MINIMIZE_DELAY_MS
            view.setMinimizeTarget(if (minimize) 1f else 0f)

            engine.update(now)
            particles.update(0.033f)
            view.setFrameTime(now)
            view.invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onResume() {
        super.onResume()
        val now = SystemClock.uptimeMillis()
        engine.resync(now)
        lastInteractionMs = now
        running = true
        Choreographer.getInstance().removeFrameCallback(frame)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onPause() {
        super.onPause()
        running = false
        sound.stopNoise()
    }

    override fun onDestroy() {
        super.onDestroy()
        gestures.release()
        sound.release()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = gestures.onKeyEvent(event)
        if (handled) lastInteractionMs = SystemClock.uptimeMillis()   // temple click = right arm
        return if (handled) true else super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Any RIGHT-arm touch counts as interaction (the left/volume pad does not).
        if (!gestures.isLeftArmDevice(ev.deviceId)) lastInteractionMs = SystemClock.uptimeMillis()
        if (gestures.onTouchEvent(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (!gestures.isLeftArmDevice(ev.deviceId)) lastInteractionMs = SystemClock.uptimeMillis()
        if (gestures.onGenericMotion(ev)) return true
        return super.dispatchGenericMotionEvent(ev)
    }

    companion object {
        private const val CONFIRM_WINDOW_MS = 5000L
        private const val MINIMIZE_DELAY_MS = 5000L
    }
}
