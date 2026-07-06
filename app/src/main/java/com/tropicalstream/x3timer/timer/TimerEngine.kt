package com.tropicalstream.x3timer.timer

import kotlin.math.ceil

/**
 * Shared countdown/interval state machine for all programs. Pomodoro phases are
 * generated on the fly (infinite focus/break alternation with flow-extend); the
 * athletic programs run a precomputed phase list.
 */
class TimerEngine {

    enum class State { IDLE, RUNNING, PAUSED, DONE }

    var program = ProgramType.POMODORO
        private set
    var state = State.IDLE
        private set

    private var phases: List<Phase> = listOf(Programs.pomodoroFocus())
    private var idx = 0
    private var phaseElapsed = 0L
    private var lastNow = 0L
    private var lastCountSec = -1

    var pomodorosCompleted = 0
        private set
    var focusStreak = 0
        private set
    var rounds = 0
        private set
    var amrapLaps = 0
        private set

    // ---- callbacks (wired to sound / particles / stores) ----
    var onPhaseEnter: ((Phase) -> Unit)? = null
    var onCountdownBeep: ((sec: Int) -> Unit)? = null
    var onFocusLogged: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null

    fun currentPhase(): Phase = phases[idx.coerceIn(0, phases.size - 1)]
    fun roundTotal(): Int = currentPhase().roundTotal

    fun phaseRemainingMs(): Long {
        val p = currentPhase()
        return if (p.countUp) phaseElapsed else (p.durationMs - phaseElapsed).coerceAtLeast(0)
    }

    fun phaseProgress(): Float {
        val p = currentPhase()
        if (p.durationMs <= 0) return if (p.countUp) 0f else 1f
        return (phaseElapsed.toFloat() / p.durationMs).coerceIn(0f, 1f)
    }

    // ---- program selection ----
    fun cycleProgram(dir: Int) {
        program = if (dir >= 0) program.next() else program.prev()
        loadProgram()
    }

    private fun loadProgram() {
        phases = when (program) {
            ProgramType.POMODORO -> listOf(Programs.pomodoroFocus())
            ProgramType.HIIT -> Programs.hiit()
            ProgramType.EMOM -> Programs.emom()
            ProgramType.AMRAP -> Programs.amrap()
        }
        idx = 0
        phaseElapsed = 0
        lastCountSec = -1
        rounds = 0
        amrapLaps = 0
        state = State.IDLE
    }

    // ---- transport ----
    fun toggleStartPause(now: Long) {
        when (state) {
            State.IDLE -> {
                state = State.RUNNING; lastNow = now
                onPhaseEnter?.invoke(currentPhase())
            }
            State.RUNNING -> state = State.PAUSED
            State.PAUSED -> { state = State.RUNNING; lastNow = now }
            State.DONE -> reset()
        }
    }

    fun reset() = loadProgram()

    /** Re-anchor the clock after a resume so paused wall-time isn't counted. */
    fun resync(now: Long) {
        lastNow = now
    }

    /** Swipe-up: pomodoro extends the current focus block (flow protection);
     *  AMRAP counts a lap; HIIT/EMOM skip to the next interval. */
    fun actionUp(now: Long) {
        when (program) {
            ProgramType.POMODORO -> {
                val p = currentPhase()
                if (p.kind == PhaseKind.FOCUS) {
                    phases = listOf(p.copy(durationMs = p.durationMs + Programs.EXTEND_MS))
                    idx = 0
                } else advance(now)
            }
            ProgramType.AMRAP -> if (state == State.RUNNING) amrapLaps++
            else -> advance(now)
        }
    }

    fun update(now: Long) {
        if (state != State.RUNNING) {
            lastNow = now
            return
        }
        // Clamp per-tick delta so a hitch or a resume can't fast-forward the clock.
        val dt = (now - lastNow).coerceIn(0, 250)
        lastNow = now
        phaseElapsed += dt
        val p = currentPhase()
        if (!p.countUp) {
            val remaining = p.durationMs - phaseElapsed
            val sec = ceil(remaining / 1000.0).toInt()
            // Sharp 3-2-1 countdown only in athletic modes (a Pomodoro focus block
            // must not beep at you — it fades to calm instead).
            if (program.athletic && sec in 1..3 && sec != lastCountSec) {
                lastCountSec = sec
                onCountdownBeep?.invoke(sec)
            }
            if (remaining <= 0) advance(now)
        } else {
            if (phaseElapsed >= p.durationMs) advance(now)
        }
    }

    private fun advance(now: Long) {
        val finished = currentPhase()
        if (finished.kind == PhaseKind.FOCUS) {
            pomodorosCompleted++
            focusStreak++
            onFocusLogged?.invoke()
        }
        if (program == ProgramType.POMODORO) {
            phases = listOf(nextPomodoro(finished))
            idx = 0
        } else {
            idx++
            if (idx >= phases.size || currentPhase().kind == PhaseKind.DONE) {
                idx = idx.coerceAtMost(phases.size - 1)
                state = State.DONE
                onDone?.invoke()
                return
            }
            val k = currentPhase().kind
            if (k == PhaseKind.WORK || k == PhaseKind.EMOM_ROUND) rounds++
        }
        phaseElapsed = 0
        lastCountSec = -1
        onPhaseEnter?.invoke(currentPhase())
    }

    private fun nextPomodoro(finished: Phase): Phase = when (finished.kind) {
        PhaseKind.FOCUS ->
            if (pomodorosCompleted % 4 == 0) Phase(PhaseKind.LONG_BREAK, "LONG BREAK", Programs.LONG_BREAK_MS)
            else Phase(PhaseKind.BREAK, "BREAK", Programs.BREAK_MS)
        else -> Programs.pomodoroFocus()
    }
}
