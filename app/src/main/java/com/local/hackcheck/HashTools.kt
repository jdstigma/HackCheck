package com.local.hackcheck

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import android.util.Base64

private const val BRUTE_FORCE_TIMEOUT_MS = 60_000L
private const val BRUTE_FORCE_MAX_COMBINATIONS = 50_000_000L
private const val BRUTE_FORCE_MAX_LENGTH = 6

/**
 * Local, CPU-only hash tools -- dictionary attack, brute force, hash-type identification, and
 * a couple of encode/decode helpers. This is NOT a hashcat port: hashcat's speed comes from GPU
 * acceleration, which a sandboxed Android app can't get without root, and there's no official
 * Android build of it. This does the same underlying job (recover a plaintext from a hash you
 * already have) with plain java.security.MessageDigest, scoped down (max brute-force length 6,
 * a combination cap, and a wall-clock timeout) so a runaway search can't hang the app.
 */
object HashTools {

    private val ALGOS = mapOf(
        "md5" to "MD5",
        "sha1" to "SHA-1",
        "sha256" to "SHA-256",
        "sha512" to "SHA-512",
    )

    private val CHARSETS = mapOf(
        "digits" to "0123456789",
        "lower" to "abcdefghijklmnopqrstuvwxyz",
        "upper" to "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
        "alnum" to "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",
        "all" to "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#\$%^&*()-_=+",
    )

    val helpText = """
        Hash commands (local, CPU-only -- not GPU-accelerated like real hashcat):
          hash <md5|sha1|sha256|sha512> <text>       Compute a hash of text
          hashid <hash>                              Guess likely hash type(s) from format/length
          crack <algo> <hash> <word1> [word2...]      Dictionary attack against given words
          bruteforce <algo> <hash> <charset> <maxLen>  charset: digits|lower|upper|alnum|all|<custom chars>
                                                        maxLen capped at $BRUTE_FORCE_MAX_LENGTH (CPU-only)
          b64encode <text> / b64decode <text>         Base64 encode/decode
          hexencode <text> / hexdecode <hex>          Hex encode/decode
          urlencode <text> / urldecode <text>         URL encode/decode
    """.trimIndent()

    suspend fun run(parts: List<String>): String? {
        return when (parts[0].lowercase()) {
            "hash" -> {
                if (parts.size < 3) "Usage: hash <md5|sha1|sha256|sha512> <text>"
                else computeHash(parts[1], parts.drop(2).joinToString(" "))
                    ?: "Unsupported algorithm \"${parts[1]}\" -- supported: ${ALGOS.keys.joinToString()}"
            }
            "hashid" -> {
                if (parts.size < 2) "Usage: hashid <hash>" else hashId(parts[1])
            }
            "crack" -> {
                if (parts.size < 4) "Usage: crack <algo> <hash> <word1> [word2...]"
                else if (ALGOS[parts[1].lowercase()] == null) "Unsupported algorithm \"${parts[1]}\""
                else crackDictionary(parts[1], parts[2], parts.drop(3))
            }
            "bruteforce", "brute" -> {
                if (parts.size < 5) "Usage: bruteforce <algo> <hash> <charset> <maxLength>"
                else {
                    val maxLen = parts[4].toIntOrNull()
                    when {
                        ALGOS[parts[1].lowercase()] == null -> "Unsupported algorithm \"${parts[1]}\""
                        maxLen == null || maxLen !in 1..BRUTE_FORCE_MAX_LENGTH ->
                            "maxLength must be 1-$BRUTE_FORCE_MAX_LENGTH (CPU-only, no GPU -- longer is impractical here)"
                        else -> bruteforce(parts[1], parts[2], parts[3], maxLen)
                    }
                }
            }
            "b64encode" -> Base64.encodeToString(parts.drop(1).joinToString(" ").toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "b64decode" -> try {
                String(Base64.decode(parts.getOrNull(1) ?: "", Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) {
                "Invalid base64: ${e.message}"
            }
            "hexencode" -> parts.drop(1).joinToString(" ").toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
            "hexdecode" -> try {
                val hex = parts.getOrNull(1) ?: ""
                String(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Charsets.UTF_8)
            } catch (e: Exception) {
                "Invalid hex: ${e.message}"
            }
            "urlencode" -> java.net.URLEncoder.encode(parts.drop(1).joinToString(" "), "UTF-8")
            "urldecode" -> try {
                java.net.URLDecoder.decode(parts.getOrNull(1) ?: "", "UTF-8")
            } catch (e: Exception) {
                "Invalid encoded string: ${e.message}"
            }
            else -> null // not a hash-tools command; let the caller try elsewhere
        }
    }

    private fun computeHash(algo: String, input: String): String? {
        val javaAlgo = ALGOS[algo.lowercase()] ?: return null
        val digest = MessageDigest.getInstance(javaAlgo).digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun hashId(hash: String): String {
        val clean = hash.trim()
        val guesses = mutableListOf<String>()
        val isHex = clean.matches(Regex("^[0-9a-fA-F]+$"))
        if (isHex) {
            when (clean.length) {
                32 -> guesses += "MD5 / NTLM (32 hex chars)"
                40 -> guesses += "SHA-1 (40 hex chars)"
                64 -> guesses += "SHA-256 (64 hex chars)"
                128 -> guesses += "SHA-512 (128 hex chars)"
            }
        }
        if (clean.startsWith("$2a$") || clean.startsWith("$2b$") || clean.startsWith("$2y$")) guesses += "bcrypt"
        if (clean.startsWith("$1$")) guesses += "MD5 crypt"
        if (clean.startsWith("$6$")) guesses += "SHA-512 crypt"
        if (clean.startsWith("\$argon2")) guesses += "Argon2"
        return if (guesses.isEmpty()) "No confident match -- length ${clean.length}, hex=${isHex}"
        else "Possible type(s): ${guesses.joinToString("; ")}"
    }

    private suspend fun crackDictionary(algo: String, targetHash: String, words: List<String>): String =
        withContext(Dispatchers.Default) {
            val target = targetHash.trim().lowercase()
            var attempts = 0
            for (word in words) {
                attempts++
                if (computeHash(algo, word) == target) return@withContext "MATCH after $attempts attempt(s): \"$word\""
            }
            "No match after $attempts attempt(s)"
        }

    private suspend fun bruteforce(algo: String, targetHash: String, charsetKey: String, maxLength: Int): String =
        withContext(Dispatchers.Default) {
            val charset = CHARSETS[charsetKey.lowercase()] ?: charsetKey
            if (charset.isEmpty()) return@withContext "Empty charset"
            val target = targetHash.trim().lowercase()

            var totalCombos = 0L
            for (len in 1..maxLength) {
                var combosAtLen = 1L
                repeat(len) { combosAtLen *= charset.length }
                totalCombos += combosAtLen
                if (totalCombos > BRUTE_FORCE_MAX_COMBINATIONS) {
                    return@withContext "Search space too large (charset size ${charset.length}, maxLength $maxLength) -- " +
                        "reduce maxLength or charset (cap: $BRUTE_FORCE_MAX_COMBINATIONS combinations)"
                }
            }

            val startMs = System.currentTimeMillis()
            var attempts = 0L
            for (len in 1..maxLength) {
                val indices = IntArray(len)
                while (true) {
                    if (System.currentTimeMillis() - startMs > BRUTE_FORCE_TIMEOUT_MS) {
                        return@withContext "Timed out after ${BRUTE_FORCE_TIMEOUT_MS / 1000}s, $attempts attempts, no match"
                    }
                    val candidate = buildString { indices.forEach { append(charset[it]) } }
                    attempts++
                    if (computeHash(algo, candidate) == target) {
                        return@withContext "MATCH after $attempts attempts: \"$candidate\""
                    }
                    var pos = len - 1
                    while (pos >= 0) {
                        indices[pos]++
                        if (indices[pos] < charset.length) break
                        indices[pos] = 0
                        pos--
                    }
                    if (pos < 0) break
                }
            }
            "No match after $attempts attempts (exhausted search space up to length $maxLength)"
        }
}
