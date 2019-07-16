package com.zeapo.pwdstore.widget

import android.os.SystemClock
import androidx.appcompat.widget.SearchView

/**
 * Based off https://gist.github.com/m7mdra/9f5d7c7ef321e29206d4309306bec376 with some cleanups.
 */
abstract class DebouncedQueryTextListener protected constructor() : SearchView.OnQueryTextListener {
    private var lastClickMillis: Long = 0

    abstract fun onQueryDebounce(text: String)

    override fun onQueryTextSubmit(query: String): Boolean {
        return true
    }

    override fun onQueryTextChange(newText: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickMillis > THRESHOLD_MILLIS) {
            onQueryDebounce(newText)
        }
        lastClickMillis = now
        return true
    }

    companion object {
        private const val THRESHOLD_MILLIS = 500L
    }
}
