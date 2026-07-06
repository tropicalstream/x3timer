package com.tropicalstream.x3timer.timer

/** The four programs. Pomodoro is the focus mode; the rest are athletic modalities. */
enum class ProgramType(val displayName: String, val athletic: Boolean) {
    POMODORO("POMODORO", false),
    HIIT("HIIT · TABATA", true),
    EMOM("EMOM", true),
    AMRAP("AMRAP", true);

    fun next() = entries[(ordinal + 1) % entries.size]
    fun prev() = entries[(ordinal - 1 + entries.size) % entries.size]
}

enum class PhaseKind { PREPARE, FOCUS, BREAK, LONG_BREAK, WORK, REST, EMOM_ROUND, AMRAP, DONE }

data class Phase(
    val kind: PhaseKind,
    val label: String,
    val durationMs: Long,
    val countUp: Boolean = false,
    val roundTotal: Int = 0
)

/** Default program timings (could later be user-configurable). */
object Programs {
    const val MIN = 60_000L

    const val FOCUS_MS = 25 * MIN
    const val BREAK_MS = 5 * MIN
    const val LONG_BREAK_MS = 15 * MIN
    const val EXTEND_MS = 10 * MIN

    fun hiit(rounds: Int = 8): List<Phase> {
        val out = ArrayList<Phase>()
        out.add(Phase(PhaseKind.PREPARE, "GET READY", 10_000L))
        for (r in 1..rounds) {
            out.add(Phase(PhaseKind.WORK, "WORK", 20_000L, roundTotal = rounds))
            if (r < rounds) out.add(Phase(PhaseKind.REST, "REST", 10_000L, roundTotal = rounds))
        }
        out.add(Phase(PhaseKind.DONE, "COMPLETE", 0L))
        return out
    }

    fun emom(rounds: Int = 10): List<Phase> {
        val out = ArrayList<Phase>()
        out.add(Phase(PhaseKind.PREPARE, "GET READY", 10_000L))
        for (r in 1..rounds) out.add(Phase(PhaseKind.EMOM_ROUND, "MIN $r", MIN, roundTotal = rounds))
        out.add(Phase(PhaseKind.DONE, "COMPLETE", 0L))
        return out
    }

    fun amrap(minutes: Int = 12): List<Phase> = listOf(
        Phase(PhaseKind.PREPARE, "GET READY", 10_000L),
        Phase(PhaseKind.AMRAP, "AMRAP", minutes * MIN, countUp = true),
        Phase(PhaseKind.DONE, "COMPLETE", 0L)
    )

    fun pomodoroFocus() = Phase(PhaseKind.FOCUS, "FOCUS", FOCUS_MS)
}
