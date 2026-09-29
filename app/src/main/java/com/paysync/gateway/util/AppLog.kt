package com.paysync.gateway.util

import android.util.Log
import com.paysync.gateway.BuildConfig

/**
 * Logging gateway for release hardening.
 *
 * Data-bearing messages (sender IDs, amounts, references, verify ids, parse
 * details) go through [d]/[i], which are no-ops in release builds — no
 * personal or transaction data ever reaches Logcat outside development.
 *
 * [w]/[e] stay enabled in release for operational diagnostics, but every call
 * site must pass only non-sensitive context: HTTP status codes, exception
 * messages from our own throws, counts. Never raw SMS bodies, secrets,
 * signatures, sender numbers or full payloads.
 */
object AppLog {

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i(tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.w(tag, message, error) else Log.w(tag, message)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.e(tag, message, error) else Log.e(tag, message)
    }
}
