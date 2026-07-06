package com.tropicalstream.x3timer.timer

import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/**
 * Frictionless velocity analytics: a per-day count of completed Pomodoros, kept
 * locally so you can see your baseline capacity (today / this week / sparkline).
 */
class StatsStore(context: Context) {

    private val prefs = context.getSharedPreferences("x3timer_stats", Context.MODE_PRIVATE)
    private val counts = HashMap<Int, Int>()

    init {
        prefs.getString("days", null)?.let { raw ->
            runCatching {
                val o = JSONObject(raw)
                o.keys().forEach { k -> counts[k.toInt()] = o.getInt(k) }
            }
        }
    }

    private fun save() {
        val o = JSONObject()
        counts.forEach { (k, v) -> o.put(k.toString(), v) }
        prefs.edit().putString("days", o.toString()).apply()
    }

    private fun dayKey(cal: Calendar): Int =
        cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)

    fun logPomodoro() {
        val k = dayKey(Calendar.getInstance())
        counts[k] = (counts[k] ?: 0) + 1
        save()
    }

    fun todayCount(): Int = counts[dayKey(Calendar.getInstance())] ?: 0

    /**
     * Consecutive days (ending today, or yesterday if nothing done yet today) with
     * at least one completed Pomodoro. Derived from the persisted daily log, so it
     * survives app restarts and device reboots.
     */
    fun dayStreak(): Int {
        var streak = 0
        val cal = Calendar.getInstance()
        if ((counts[dayKey(cal)] ?: 0) == 0) cal.add(Calendar.DAY_OF_YEAR, -1)
        while ((counts[dayKey(cal)] ?: 0) > 0) {
            streak++
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }

    /** Last 7 days, oldest → today, for a sparkline. */
    fun lastWeek(): IntArray {
        val out = IntArray(7)
        for (i in 6 downTo 0) {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, -i)
            out[6 - i] = counts[dayKey(c)] ?: 0
        }
        return out
    }
}
