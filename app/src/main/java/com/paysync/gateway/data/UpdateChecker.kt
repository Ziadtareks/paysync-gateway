package com.paysync.gateway.data

import com.google.gson.JsonParser
import com.paysync.gateway.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Opt-out update checker: compares the latest published GitHub release with
 * the installed version and reports whether a newer one exists.
 *
 * Guarantees (see PRIVACY.md):
 *  - One anonymous HTTPS GET to api.github.com, at most once per 24 h
 *    ([SettingsManager.lastUpdateCheckAt] throttle, set by the caller).
 *  - Uses its own plain [OkHttpClient] with NO HmacInterceptor — the gateway
 *    signing secret is never sent to GitHub.
 *  - Never downloads or installs anything; the caller only shows a notice
 *    with a link to the releases page.
 *  - Any error (offline, rate-limited 403, malformed JSON) returns null and
 *    is swallowed silently.
 */
class UpdateChecker {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(val latestVersion: String, val releasesUrl: String)

    suspend fun checkLatestRelease(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val body = res.body?.string() ?: return@withContext null
                val obj = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
                    ?: return@withContext null
                val tag = obj.get("tag_name")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: return@withContext null
                val url = obj.get("html_url")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: RELEASES_URL
                UpdateInfo(latestVersion = tag.removePrefix("v").removePrefix("V"), releasesUrl = url)
            }
        } catch (_: Exception) {
            null // offline / rate-limited / malformed — stay silent
        }
    }

    companion object {
        const val RELEASES_LATEST_URL =
            "https://api.github.com/repos/Ziadtareks/paysync-gateway/releases/latest"
        const val RELEASES_URL =
            "https://github.com/Ziadtareks/paysync-gateway/releases"

        /**
         * Numeric semver-ish comparison: "1.10.0" > "1.9.5", equal prefixes
         * with more components win, pre-release suffixes ("-rc1") are ignored
         * so pre-releases never trigger the notice. Unparseable input is
         * never "newer" (fail silent).
         */
        fun isNewer(latest: String, current: String = BuildConfig.VERSION_NAME): Boolean {
            val l = latest.removePrefix("v").removePrefix("V").substringBefore('-').split('.').map { it.toLongOrNull() }
            val c = current.removePrefix("v").removePrefix("V").substringBefore('-').split('.').map { it.toLongOrNull() }
            if (l.any { it == null } || c.any { it == null }) return false
            val ln = l.map { it!! }
            val cn = c.map { it!! }
            for (i in 0 until maxOf(ln.size, cn.size)) {
                val lv = ln.getOrElse(i) { 0L }
                val cv = cn.getOrElse(i) { 0L }
                if (lv != cv) return lv > cv
            }
            return false
        }
    }
}
