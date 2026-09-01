package com.yourapp.audiobook.data.sync

import android.content.Context

/**
 * Хранит время последнего локального изменения данных приложения.
 * Нужно для корректного слияния при синхронизации: данные той стороны,
 * которая менялась позже, считаются актуальными (last-write-wins).
 */
class SyncStateStore(context: Context) {

    private val prefs = context.getSharedPreferences("sync_state", Context.MODE_PRIVATE)

    fun lastChangeMs(): Long = prefs.getLong(KEY_LAST_CHANGE_MS, 0L)

    fun recordChange() {
        prefs.edit().putLong(KEY_LAST_CHANGE_MS, System.currentTimeMillis()).apply()
    }

    companion object {
        private const val KEY_LAST_CHANGE_MS = "lastChangeMs"
    }
}
