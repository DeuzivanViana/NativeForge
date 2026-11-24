package io.nativeforge.core.remote.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** A pairing code, as the phone shows it. */
data class PairingCode(val code: String, val expiresAtMillis: Long)

/** A browser that paired with this device. Only a hash of its token is kept. */
data class TrustedBrowser(
    val id: String,
    val label: String,
    val createdAtMillis: Long,
    val lastSeenAtMillis: Long,
)

/** Result of a pairing attempt. */
sealed interface PairingResult {
    /** [token] goes into the browser's cookie; the server keeps only its hash. */
    data class Paired(val token: String, val browser: TrustedBrowser) : PairingResult
    data object WrongCode : PairingResult
    /** No code is active (expired, used, or locked after too many wrong attempts). */
    data object NoActiveCode : PairingResult
    data object TooManyAttempts : PairingResult
}

/**
 * Pairing codes and the session tokens of trusted browsers.
 *
 * * A pairing code is 8 characters from a 31-letter alphabet without look-alikes (~40 bits),
 *   valid for [codeLifetimeMillis] and for one browser only. After [maxFailures] wrong
 *   attempts the code is destroyed, so guessing it online is hopeless.
 * * A paired browser gets a 256-bit random token. Only its SHA-256 is stored (in [storeFile],
 *   so trusted browsers survive restarts), compared in constant time, and forgotten after
 *   [sessionLifetimeMillis] without use. The user can revoke one browser or all of them.
 */
class AccessControl(
    private val storeFile: File?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val codeLifetimeMillis: Long = TimeUnit.MINUTES.toMillis(5),
    private val sessionLifetimeMillis: Long = TimeUnit.DAYS.toMillis(30),
    private val maxFailures: Int = 5,
    private val maxBrowsers: Int = 8,
) {
    private val random = SecureRandom()
    private val lock = Any()
    private var activeCode: PairingCode? = null
    private var failures = 0
    private val sessions = LinkedHashMap<String, Entry>() // token hash (hex) -> entry
    private var lastPersistedAt = 0L

    private data class Entry(val browser: TrustedBrowser)

    @Volatile var onChanged: () -> Unit = {}

    init { load() }

    /** Creates a fresh pairing code, replacing the previous one. */
    fun newPairingCode(): PairingCode = synchronized(lock) {
        val code = buildString { repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
        PairingCode(code, clock() + codeLifetimeMillis).also {
            activeCode = it
            failures = 0
        }
    }.also { onChanged() }

    /** The code the phone should display, or null when none is valid. */
    fun currentPairingCode(): PairingCode? = synchronized(lock) {
        activeCode?.takeIf { clock() < it.expiresAtMillis }
    }

    fun cancelPairingCode() {
        synchronized(lock) { activeCode = null }
        onChanged()
    }

    /** Exchanges [typed] for a session token when it matches the active code. */
    fun pair(typed: String, label: String): PairingResult {
        val result = synchronized(lock) {
            val code = activeCode?.takeIf { clock() < it.expiresAtMillis } ?: return@synchronized PairingResult.NoActiveCode
            val normalized = normalizeCode(typed)
            if (!MessageDigest.isEqual(normalized.toByteArray(), code.code.toByteArray())) {
                failures++
                if (failures >= maxFailures) {
                    activeCode = null
                    return@synchronized PairingResult.TooManyAttempts
                }
                return@synchronized PairingResult.WrongCode
            }
            activeCode = null
            failures = 0
            val token = newToken()
            val now = clock()
            val browser = TrustedBrowser(id = newId(), label = label.take(80).ifBlank { "Browser" }, createdAtMillis = now, lastSeenAtMillis = now)
            sessions[hash(token)] = Entry(browser)
            // Keep the most recently used browsers when over the limit.
            while (sessions.size > maxBrowsers) {
                val oldest = sessions.minByOrNull { it.value.browser.lastSeenAtMillis }!!.key
                sessions.remove(oldest)
            }
            persistLocked(force = true)
            PairingResult.Paired(token, browser)
        }
        onChanged()
        return result
    }

    /** The browser a token belongs to, refreshing its last use; null when unknown or expired. */
    fun authenticate(token: String?): TrustedBrowser? {
        if (token.isNullOrEmpty() || token.length > 128) return null
        val key = hash(token)
        return synchronized(lock) {
            pruneLocked()
            // HashMap lookup by the hash is safe: the hash of a guess reveals nothing about stored hashes.
            val entry = sessions[key] ?: return@synchronized null
            val refreshed = entry.browser.copy(lastSeenAtMillis = clock())
            sessions[key] = Entry(refreshed)
            persistLocked(force = false)
            refreshed
        }
    }

    fun logout(token: String?) {
        if (token == null) return
        synchronized(lock) { if (sessions.remove(hash(token)) != null) persistLocked(force = true) }
        onChanged()
    }

    fun trustedBrowsers(): List<TrustedBrowser> = synchronized(lock) {
        pruneLocked()
        sessions.values.map { it.browser }.sortedByDescending { it.lastSeenAtMillis }
    }

    fun revoke(browserId: String) {
        synchronized(lock) {
            sessions.entries.removeAll { it.value.browser.id == browserId }
            persistLocked(force = true)
        }
        onChanged()
    }

    fun revokeAll() {
        synchronized(lock) {
            sessions.clear()
            persistLocked(force = true)
        }
        onChanged()
    }

    /** True if [token] is the session of the browser [browserId] (to close its connections on revoke). */
    fun tokenBelongsTo(token: String, browserId: String): Boolean =
        synchronized(lock) { sessions[hash(token)]?.browser?.id == browserId }

    private fun pruneLocked() {
        val cutoff = clock() - sessionLifetimeMillis
        if (sessions.entries.removeAll { it.value.browser.lastSeenAtMillis < cutoff }) persistLocked(force = true)
    }

    private fun newToken(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun newId(): String = ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun persistLocked(force: Boolean) {
        val file = storeFile ?: return
        val now = clock()
        // Last-seen times only need minute precision on disk; avoid a write per request.
        if (!force && now - lastPersistedAt < TimeUnit.MINUTES.toMillis(1)) return
        lastPersistedAt = now
        val json = JsonObject(
            mapOf(
                "version" to JsonPrimitive(1),
                "sessions" to JsonArray(
                    sessions.map { (hash, entry) ->
                        JsonObject(
                            mapOf(
                                "hash" to JsonPrimitive(hash),
                                "id" to JsonPrimitive(entry.browser.id),
                                "label" to JsonPrimitive(entry.browser.label),
                                "created" to JsonPrimitive(entry.browser.createdAtMillis),
                                "seen" to JsonPrimitive(entry.browser.lastSeenAtMillis),
                            ),
                        )
                    },
                ),
            ),
        )
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        }
    }

    private fun load() {
        val file = storeFile ?: return
        if (!file.isFile) return
        runCatching {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            root["sessions"]?.jsonArray?.forEach { element ->
                val o = element.jsonObject
                val hash = o["hash"]!!.jsonPrimitive.content
                if (hash.length != 64) return@forEach
                sessions[hash] = Entry(
                    TrustedBrowser(
                        id = o["id"]!!.jsonPrimitive.content,
                        label = o["label"]?.jsonPrimitive?.content ?: "Browser",
                        createdAtMillis = o["created"]!!.jsonPrimitive.long,
                        lastSeenAtMillis = o["seen"]!!.jsonPrimitive.long,
                    ),
                )
            }
        }
    }

    companion object {
        /** No 0/O, 1/I/L: easy to read on a phone and type on a PC. */
        const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
        const val CODE_LENGTH = 8

        /** Upper-cases and drops spaces and dashes, so "abcd-efgh" matches "ABCDEFGH". */
        fun normalizeCode(typed: String): String = typed.uppercase().filter { it in ALPHABET }.take(CODE_LENGTH * 2)

        /** "ABCD-EFGH" for display. */
        fun formatCode(code: String): String = code.chunked(4).joinToString("-")

        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
