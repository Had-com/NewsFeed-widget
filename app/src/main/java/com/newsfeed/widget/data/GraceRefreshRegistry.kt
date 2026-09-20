package com.newsfeed.widget.data

import java.util.concurrent.ConcurrentHashMap

/**
 * In-process record of which just-read articles already have their dissolve/removal refreshes
 * scheduled, keyed by widget + article + readAt. Lets both the tap callbacks and the render
 * itself (which resumes the schedule after a process restart) call the scheduler freely
 * without ever double-scheduling, so re-renders triggered by the scheduled updates cannot fan
 * out into more schedules. The entry is released when that article's schedule completes.
 * A different readAt is a different key: an article re-read later gets its own schedule.
 */
class GraceRefreshRegistry {
    private val active: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** True if the caller now owns the schedule for [key]; false if one is already running. */
    fun tryRegister(key: String): Boolean = active.add(key)

    fun release(key: String) {
        active.remove(key)
    }

    companion object {
        fun key(widgetKey: String, articleId: String, readAt: Long): String =
            "$widgetKey|$articleId|$readAt"
    }
}
