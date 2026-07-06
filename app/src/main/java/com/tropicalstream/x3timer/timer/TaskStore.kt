package com.tropicalstream.x3timer.timer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight local task list for Pomodoro "direct task binding". The current
 * focus block is bound to [currentTask]; completing a focus logs a tomato against
 * it automatically. Persisted as JSON in SharedPreferences.
 */
class TaskStore(context: Context) {

    data class Task(val name: String, var tomatoes: Int)

    private val prefs = context.getSharedPreferences("x3timer_tasks", Context.MODE_PRIVATE)
    private val tasks = ArrayList<Task>()
    private var current = 0

    init {
        load()
    }

    private fun load() {
        tasks.clear()
        prefs.getString("tasks", null)?.let { raw ->
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    tasks.add(Task(o.getString("name"), o.optInt("tomatoes", 0)))
                }
            }
        }
        if (tasks.isEmpty()) {
            tasks.add(Task("Deep Work", 0))
            tasks.add(Task("Code Review", 0))
            tasks.add(Task("Writing", 0))
            tasks.add(Task("Learning", 0))
        }
        current = prefs.getInt("current", 0).coerceIn(0, tasks.size - 1)
        save()
    }

    private fun save() {
        val arr = JSONArray()
        tasks.forEach { arr.put(JSONObject().put("name", it.name).put("tomatoes", it.tomatoes)) }
        prefs.edit().putString("tasks", arr.toString()).putInt("current", current).apply()
    }

    fun currentTask(): Task = tasks[current.coerceIn(0, tasks.size - 1)]

    fun cycle() {
        current = (current + 1) % tasks.size
        save()
    }

    fun logTomato() {
        currentTask().tomatoes++
        save()
    }
}
