package com.tropicalstream.x3timer.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.tropicalstream.x3timer.fx.Particles
import com.tropicalstream.x3timer.timer.PhaseKind
import com.tropicalstream.x3timer.timer.ProgramType
import com.tropicalstream.x3timer.timer.StatsStore
import com.tropicalstream.x3timer.timer.TaskStore
import com.tropicalstream.x3timer.timer.TimerEngine
import kotlin.math.max
import kotlin.math.sin

/**
 * The glanceable HUD. Core widget occupies < 20% of the screen (lower-centre,
 * peripheral); everything else is black = transparent on the waveguide. Status is
 * color-coded and echoed on a thin screen-edge pulse so it reads from the corner
 * of the eye. Pomodoro stays dim during deep focus and brightens (to calm blue) in
 * the final minute; athletic modes are always bold, high-contrast, synthwave.
 */
class TimerView(
    context: Context,
    private val engine: TimerEngine,
    private val particles: Particles,
    private val tasks: TaskStore,
    private val stats: StatsStore
) : View(context) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private var vw = 640
    private var vh = 480
    private val panel = RectF()          // working rect (lerps full → corner)
    private val fullPanel = RectF()
    private val cornerPanel = RectF()
    private val chip = RectF()
    private var frameTime = 0L
    private var switchDir = 0
    private var switchFrac = 0f
    private var minimizeT = 0f           // 0 = full, 1 = minimized corner
    private var minimizeTarget = 0f

    fun setFrameTime(now: Long) {
        frameTime = now
    }

    /** Mode-switch confirmation banner: [dir] 0 = hidden, [fracLeft] shrinks 1→0. */
    fun setSwitchPrompt(dir: Int, fracLeft: Float) {
        switchDir = dir
        switchFrac = fracLeft
    }

    /** Target for the Pomodoro auto-minimize (0 = full size, 1 = corner chip). */
    fun setMinimizeTarget(t: Float) {
        minimizeTarget = t
    }

    // Reward bursts (fired by the Activity on interval/round/workout completion).
    fun rewardWork() = particles.burst(panel.centerX(), panel.top, VGREEN, 22, 240f)
    fun rewardRound() = particles.burst(panel.centerX(), panel.top, CYAN, 26, 260f)
    fun rewardDone() = particles.burst(panel.centerX(), panel.centerY(), GOLD, 72, 340f)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        vw = w; vh = h
        // Full size: 0.50 × 0.32 = 16% of screen area, lower-centre.
        val pw = w * 0.50f
        val ph = h * 0.32f
        val cx = w / 2f
        val top = h * 0.60f
        fullPanel.set(cx - pw / 2f, top, cx + pw / 2f, top + ph)
        // Minimized: small chip tucked into the upper-left corner (~4.5% area).
        val mw = w * 0.30f
        val mh = h * 0.15f
        cornerPanel.set(w * 0.03f, h * 0.05f, w * 0.03f + mw, h * 0.05f + mh)
        panel.set(fullPanel)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)

        // Ease the minimize factor and derive the working panel rect. All panel
        // content is sized relative to this rect, so it shrinks/grows as one.
        minimizeT += (minimizeTarget - minimizeT) * 0.16f
        if (kotlin.math.abs(minimizeTarget - minimizeT) < 0.004f) minimizeT = minimizeTarget
        panel.set(
            lerp(fullPanel.left, cornerPanel.left, minimizeT),
            lerp(fullPanel.top, cornerPanel.top, minimizeT),
            lerp(fullPanel.right, cornerPanel.right, minimizeT),
            lerp(fullPanel.bottom, cornerPanel.bottom, minimizeT)
        )

        drawEdgePulse(canvas)
        if (engine.program.athletic && engine.state == TimerEngine.State.RUNNING) drawSynthGrid(canvas)
        if (engine.state == TimerEngine.State.IDLE) drawIdleHelp(canvas)
        drawPanel(canvas)
        drawSwitchPrompt(canvas)
        particles.draw(canvas)
    }

    /** "Swipe again to switch" confirmation with a shrinking 5s countdown bar. */
    private fun drawSwitchPrompt(canvas: Canvas) {
        if (switchDir == 0) return
        val target = if (switchDir >= 0) engine.program.next() else engine.program.prev()
        val arrow = if (switchDir >= 0) "→" else "←"
        val msg = "SWITCH TO ${target.displayName}?  SWIPE $arrow AGAIN"

        label.textAlign = Paint.Align.CENTER
        fitText(label, msg, vw * 0.92f, vh * 0.05f, vh * 0.03f)
        val chipW = label.measureText(msg) + vw * 0.06f
        val chipH = label.textSize * 2.6f
        val cx = vw / 2f
        val cy = panel.top - vh * 0.12f
        chip.set(cx - chipW / 2f, cy - chipH / 2f, cx + chipW / 2f, cy + chipH / 2f)

        p.style = Paint.Style.FILL
        p.color = PANEL_FILL
        p.alpha = 215
        canvas.drawRoundRect(chip, 10f, 10f, p)

        val pulse = 0.55f + 0.45f * sin(frameTime / 120f)
        stroke.color = AMBER
        stroke.strokeWidth = 2.5f
        stroke.alpha = (pulse * 255).toInt().coerceIn(60, 255)
        canvas.drawRoundRect(chip, 10f, 10f, stroke)

        label.color = AMBER
        label.alpha = 255
        label.setShadowLayer(label.textSize * 0.3f, 0f, 0f, AMBER)
        canvas.drawText(msg, cx, cy - chipH * 0.05f, label)
        label.clearShadowLayer()

        val barL = chip.left + vw * 0.02f
        val barR = chip.right - vw * 0.02f
        val barY = chip.bottom - chipH * 0.24f
        val barH = chipH * 0.10f
        p.color = AMBER
        p.alpha = 55
        canvas.drawRoundRect(barL, barY, barR, barY + barH, 3f, 3f, p)
        p.alpha = 230
        canvas.drawRoundRect(barL, barY, barL + (barR - barL) * switchFrac, barY + barH, 3f, 3f, p)
    }

    // ---- status colour + intensity ----

    private fun remainingMs() = engine.phaseRemainingMs()

    private fun finalMinute(): Boolean =
        engine.program == ProgramType.POMODORO &&
            engine.currentPhase().kind == PhaseKind.FOCUS &&
            engine.state == TimerEngine.State.RUNNING &&
            remainingMs() in 1..60_000

    private fun athleticWarning(): Boolean =
        engine.program.athletic &&
            engine.state == TimerEngine.State.RUNNING &&
            !engine.currentPhase().countUp &&
            remainingMs() in 1..5_000

    private fun accent(): Int {
        if (engine.state == TimerEngine.State.IDLE) return WHITE_DIM
        if (engine.state == TimerEngine.State.DONE) return GOLD
        return when (engine.currentPhase().kind) {
            PhaseKind.FOCUS -> if (finalMinute()) BLUE else TEAL
            PhaseKind.BREAK, PhaseKind.LONG_BREAK -> GREEN_CALM
            PhaseKind.PREPARE -> AMBER
            PhaseKind.WORK -> VGREEN
            PhaseKind.REST -> CRIMSON
            PhaseKind.EMOM_ROUND -> CYAN
            PhaseKind.AMRAP -> MAGENTA
            PhaseKind.DONE -> GOLD
        }
    }

    private fun intensity(): Float = when {
        engine.state == TimerEngine.State.DONE -> 1f
        engine.state == TimerEngine.State.IDLE -> 0.7f
        engine.program.athletic -> 1f
        // Focus stays readable; unobtrusiveness now comes from auto-minimize, not dimming.
        engine.currentPhase().kind == PhaseKind.FOCUS -> if (finalMinute()) 1f else 0.85f
        else -> 0.8f
    }

    // ---- edge pulse (peripheral awareness) ----

    private fun drawEdgePulse(canvas: Canvas) {
        val warn = athleticWarning()
        val show = warn ||
            engine.state == TimerEngine.State.DONE ||
            (engine.state == TimerEngine.State.RUNNING && (engine.program.athletic || finalMinute()))
        if (!show) return
        val color = if (warn) AMBER else accent()
        val a = if (warn) {
            if ((frameTime / 140) % 2 == 0L) 0.9f else 0.12f     // hard flash
        } else {
            0.22f + 0.20f * (0.5f + 0.5f * sin(frameTime / 480f))
        }
        stroke.color = color
        stroke.alpha = (a * 255).toInt().coerceIn(0, 255)
        stroke.strokeWidth = vh * 0.012f
        val inset = stroke.strokeWidth
        canvas.drawRect(inset, inset, vw - inset, vh - inset, stroke)
    }

    private fun drawSynthGrid(canvas: Canvas) {
        // Faint synthwave floor lines behind the panel (athletic only).
        stroke.color = MAGENTA
        stroke.alpha = 26
        stroke.strokeWidth = 1.5f
        val horizon = panel.top - vh * 0.04f
        var y = horizon
        var d = vh * 0.03f
        while (y < vh) {
            canvas.drawLine(0f, y, vw.toFloat(), y, stroke)
            y += d; d *= 1.35f
        }
        var i = -5
        while (i <= 5) {
            canvas.drawLine(vw / 2f, horizon, vw / 2f + i * vw * 0.16f, vh.toFloat(), stroke)
            i++
        }
    }

    // ---- the panel ----

    private fun drawPanel(canvas: Canvas) {
        val color = accent()
        // Keep the minimized corner chip legible even if the state's base intensity is low.
        val inten = max(intensity(), minimizeT * 0.85f)
        val compact = minimizeT > 0.5f      // corner chip: phase + time only
        val h = panel.height()
        val pad = h * 0.12f
        val innerW = panel.width() - pad * 2f

        // Fill + glowing border.
        p.style = Paint.Style.FILL
        p.color = PANEL_FILL
        p.alpha = (200 * inten).toInt().coerceIn(30, 220)
        canvas.drawRoundRect(panel, 14f, 14f, p)
        stroke.color = color
        for (g in 4 downTo 1) {
            stroke.alpha = ((10 + (4 - g) * 6) * inten).toInt().coerceIn(0, 255)
            stroke.strokeWidth = 2.5f + g * 3f
            canvas.drawRoundRect(panel, 14f, 14f, stroke)
        }
        stroke.alpha = (255 * inten).toInt().coerceIn(40, 255)
        stroke.strokeWidth = 2.5f
        canvas.drawRoundRect(panel, 14f, 14f, stroke)

        // Everything is CENTERED and shrink-to-fit, so nothing can overlap.
        label.textAlign = Paint.Align.CENTER
        label.color = color

        // Overline — PROGRAM · PHASE (full) or just PHASE (compact corner chip).
        val over = if (compact) engine.currentPhase().label else headline()
        fitText(label, over, innerW, h * 0.15f, h * 0.085f)
        label.setShadowLayer(label.textSize * 0.35f, 0f, 0f, color)
        label.alpha = (230 * inten).toInt().coerceIn(60, 255)
        canvas.drawText(over, panel.centerX(), panel.top + pad + label.textSize * 0.85f, label)
        label.clearShadowLayer()

        // Time — dominant; nudged to vertical centre when compact (no bar/caption).
        digits.textSize = h * (if (compact) 0.46f else 0.40f)
        digits.color = color
        digits.alpha = (255 * inten).toInt().coerceIn(70, 255)
        digits.setShadowLayer(digits.textSize * 0.22f, 0f, 0f, color)
        canvas.drawText(timeText(), panel.centerX(), panel.top + h * (if (compact) 0.78f else 0.62f), digits)
        digits.clearShadowLayer()

        if (compact) return                 // corner chip stops here

        // Progress bar.
        val barY = panel.bottom - pad * 1.55f
        val barL = panel.left + pad
        val barR = panel.right - pad
        p.color = color
        p.alpha = (40 * inten).toInt().coerceIn(15, 90)
        canvas.drawRoundRect(barL, barY, barR, barY + h * 0.045f, 4f, 4f, p)
        val prog = engine.phaseProgress()
        p.alpha = (235 * inten).toInt().coerceIn(60, 255)
        canvas.drawRoundRect(barL, barY, barL + (barR - barL) * prog, barY + h * 0.045f, 4f, 4f, p)

        // Caption — task + analytics (pomodoro) / rounds / laps.
        val cap = caption()
        if (cap.isNotEmpty()) {
            label.color = color
            fitText(label, cap, innerW, h * 0.10f, h * 0.06f)
            label.alpha = (180 * inten).toInt().coerceIn(50, 220)
            canvas.drawText(cap, panel.centerX(), panel.bottom - pad * 0.25f, label)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Shrink [paint]'s text size until [s] fits [maxWidth] (down to [minSize]). */
    private fun fitText(paint: Paint, s: String, maxWidth: Float, startSize: Float, minSize: Float) {
        var size = startSize
        paint.textSize = size
        while (size > minSize && paint.measureText(s) > maxWidth) {
            size -= 1f
            paint.textSize = size
        }
    }

    private fun drawIdleHelp(canvas: Canvas) {
        label.textAlign = Paint.Align.CENTER
        label.color = WHITE_DIM
        label.alpha = 150
        label.textSize = vh * 0.045f
        label.clearShadowLayer()
        canvas.drawText("TAP start   •   DOUBLE-TAP reset", vw / 2f, vh * 0.34f, label)
        canvas.drawText("SWIPE  ←→ mode   ↑ extend/skip   ↓ task/mute", vw / 2f, vh * 0.42f, label)
        // Pomodoro 7-day sparkline (cheap velocity glance).
        if (engine.program == ProgramType.POMODORO) drawSparkline(canvas)
    }

    private fun drawSparkline(canvas: Canvas) {
        val week = stats.lastWeek()
        val peak = max(1, week.max())
        val bw = vw * 0.03f
        val gap = vw * 0.012f
        val totalW = week.size * bw + (week.size - 1) * gap
        var x = vw / 2f - totalW / 2f
        val baseY = vh * 0.52f
        val maxH = vh * 0.06f
        p.style = Paint.Style.FILL
        for (v in week) {
            val h = maxH * (v.toFloat() / peak)
            p.color = if (v > 0) TEAL else WHITE_DIM
            p.alpha = if (v > 0) 200 else 60
            canvas.drawRoundRect(x, baseY - h, x + bw, baseY, 2f, 2f, p)
            x += bw + gap
        }
    }

    // ---- text helpers ----

    private fun headline(): String = "${engine.program.displayName} · ${engine.currentPhase().label}"

    private fun caption(): String = when {
        engine.state == TimerEngine.State.DONE -> "COMPLETE"
        engine.program == ProgramType.POMODORO ->
            "${tasks.currentTask().name.uppercase()}  •  TODAY ${stats.todayCount()}  •  STREAK ${stats.dayStreak()}"
        engine.program == ProgramType.AMRAP -> "LAP ${engine.amrapLaps}"
        engine.roundTotal() > 0 -> "ROUND ${engine.rounds} / ${engine.roundTotal()}"
        else -> ""
    }

    private fun timeText(): String {
        // remainingMs() already returns elapsed for count-up (AMRAP) phases.
        val totalSec = remainingMs() / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    companion object {
        private val WHITE_DIM = 0xFFB0B8C0.toInt()
        private val TEAL = 0xFF5EEAD4.toInt()
        private val BLUE = 0xFF60A5FA.toInt()
        private val GREEN_CALM = 0xFF34D399.toInt()
        private val AMBER = 0xFFFFB300.toInt()
        private val VGREEN = 0xFF22FF88.toInt()
        private val CRIMSON = 0xFFFF2D55.toInt()
        private val CYAN = 0xFF00E5FF.toInt()
        private val MAGENTA = 0xFFFF4DD8.toInt()
        private val GOLD = 0xFFFFD54A.toInt()
        private val PANEL_FILL = 0xFF0A0E14.toInt()
    }
}
