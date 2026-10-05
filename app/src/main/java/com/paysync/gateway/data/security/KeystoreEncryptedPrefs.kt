package com.paysync.gateway.data.security

import android.content.SharedPreferences
import com.paysync.gateway.util.AppLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * [SharedPreferences] whose values are encrypted with [codec] (AES-256-GCM,
 * key in the Android Keystore) inside an ordinary prefs file [backing].
 * Key NAMES stay readable (they are fixed constants, not data); every VALUE
 * is ciphertext bound to its key.
 *
 * All values are decrypted once when opened and served from memory, so the
 * hot paths (heartbeats, poll intervals) never hit the Keystore on read;
 * writes encrypt then update disk and memory together. Single-process app,
 * so this instance is the only writer of [backing].
 *
 * A value that fails to decrypt (tampered, or its key was lost) is reported
 * in [undecryptableKeys] and reads as absent — never a crash.
 */
class KeystoreEncryptedPrefs(
    private val backing: SharedPreferences,
    private val codec: AesGcmValueCodec
) : SharedPreferences {

    private val cache = ConcurrentHashMap<String, Any>()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val lock = Any()

    /** Keys present on disk whose value could not be decrypted at open time. */
    val undecryptableKeys: Set<String>

    init {
        val failed = HashSet<String>()
        for ((key, stored) in backing.all) {
            val value = (stored as? String)?.let { codec.decrypt(key, it) }
            if (value != null) cache[key] = value else failed += key
        }
        undecryptableKeys = failed
        if (failed.isNotEmpty()) {
            AppLog.w(TAG, "${failed.size} encrypted setting(s) could not be decrypted; using defaults")
        }
    }

    override fun getAll(): MutableMap<String, *> =
        HashMap<String, Any>(cache).apply { keys.removeAll { it.startsWith(INTERNAL_PREFIX) } }

    override fun getString(key: String, defValue: String?): String? = cache[key] as? String ?: defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        val v = cache[key] as? Set<String> ?: return defValues
        return HashSet(v)
    }

    override fun getInt(key: String, defValue: Int): Int = cache[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = cache[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = cache[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = cache[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = cache.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners += l
    }

    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners -= l
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val puts = LinkedHashMap<String, Any>()
        private val removes = LinkedHashSet<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values?.let { HashSet(it) })
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)

        override fun remove(key: String): SharedPreferences.Editor = apply {
            puts.remove(key)
            removes += key
        }

        override fun clear(): SharedPreferences.Editor = apply { clear = true }

        private fun put(key: String, value: Any?): SharedPreferences.Editor = apply {
            if (value == null) {
                remove(key)
            } else {
                removes.remove(key)
                puts[key] = value
            }
        }

        override fun commit(): Boolean = write(sync = true)

        override fun apply() {
            write(sync = false)
        }

        /**
         * Encrypts EVERYTHING first: if the Keystore fails on any value,
         * nothing is written (no half-applied edit) and the call reports false.
         */
        private fun write(sync: Boolean): Boolean {
            val changed = ArrayList<String>()
            val ok = synchronized(lock) {
                val encrypted = try {
                    puts.mapValues { (k, v) -> codec.encrypt(k, v) }
                } catch (e: Exception) {
                    AppLog.e(TAG, "Encrypting settings failed; edit not saved", e)
                    return@synchronized false
                }
                val ed = backing.edit()
                if (clear) {
                    ed.clear()
                    changed += cache.keys
                    cache.clear()
                }
                for (k in removes) {
                    ed.remove(k)
                    if (cache.remove(k) != null) changed += k
                }
                for ((k, enc) in encrypted) {
                    ed.putString(k, enc)
                    cache[k] = puts.getValue(k)
                    changed += k
                }
                if (sync) ed.commit() else {
                    ed.apply()
                    true
                }
            }
            if (ok) for (k in changed) for (l in listeners) l.onSharedPreferenceChanged(this@KeystoreEncryptedPrefs, k)
            return ok
        }
    }

    companion object {
        private const val TAG = "EncryptedPrefs"

        /** Keys with this prefix are storage bookkeeping, hidden from [getAll]. */
        const val INTERNAL_PREFIX = "__paysync_"
    }
}
