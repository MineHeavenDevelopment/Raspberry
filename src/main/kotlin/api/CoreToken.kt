package api

import config.ApiConfig
import utils.logger
import java.security.MessageDigest

// Canonical core API token (P0 hardening).
//
// Resolution order:
//   1. environment variable LUMINOUS_CORE_TOKEN  (deployment secret, never in git)
//   2. config [api] core_token                   (canonical config key)
//   3. config [api] admin_token                  (legacy alias, read for compatibility)
//   4. built-in dev default                      (only for local development; loud WARN)
//
// The legacy header "X-Admin-Token" is still accepted as an alias of
// "X-Core-Token" so existing producers keep working during migration.
object CoreToken {
    private const val ENV_VAR = "LUMINOUS_CORE_TOKEN"
    private const val DEV_DEFAULT = "change-me-core-admin-token"

    @Volatile
    private var warnedDeprecatedHeader = false

    fun applyConfig(cfg: ApiConfig) {
        val fromEnv = System.getenv(ENV_VAR)?.trim().orEmpty()
        active = when {
            fromEnv.isNotEmpty() -> fromEnv
            cfg.coreToken.isNotBlank() -> cfg.coreToken
            cfg.adminToken.isNotBlank() -> cfg.adminToken
            else -> DEV_DEFAULT
        }
        warnOnceIfNeeded(active)
    }

    @Volatile
    var active: String = DEV_DEFAULT
        private set

    private fun warnOnceIfNeeded(token: String) {
        if (token == DEV_DEFAULT) {
            logger(
                "WARNING: core API uses the built-in default token. " +
                    "Set [api].core_token in config.toml or the LUMINOUS_CORE_TOKEN environment variable before production!",
                error = true
            )
        }
    }

    fun warnDeprecatedHeaderOnce() {
        if (warnedDeprecatedHeader) return
        warnedDeprecatedHeader = true
        logger("Header 'X-Admin-Token' is deprecated; use 'X-Core-Token' (accepted as alias).", error = false)
    }

    // Constant-time comparison; compares fixed-size SHA-256 digests so token
    // length is never leaked through timing.
    fun matches(candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val a = sha256(candidate)
        val b = sha256(active)
        return MessageDigest.isEqual(a, b)
    }

    private fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
}
