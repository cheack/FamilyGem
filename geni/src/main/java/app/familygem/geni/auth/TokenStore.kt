package app.familygem.geni.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Keeps the Geni access token. */
interface TokenStore {
    fun get(): AuthResult.Success?
    fun save(token: AuthResult.Success)
    fun clear()

    /** State of the login started in the browser, null when no login is pending. */
    var pendingState: String?
}

/** Token store in encrypted shared preferences. */
@Suppress("DEPRECATION") // security-crypto has no replacement yet; the app module uses it too
class EncryptedTokenStore(context: Context) : TokenStore {

    // A keyset that no longer matches the Keystore key (after restore, device transfer or Keystore reset)
    // makes create() throw: the stored token is lost anyway, so the file is deleted and the user logs in again
    private val preferences: SharedPreferences = openOrReset(
        open = {
            EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        },
        reset = { context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit(commit = true) { clear() } }
    )

    override fun get(): AuthResult.Success? {
        val token = preferences.getString(TOKEN, null) ?: return null
        return AuthResult.Success(token, preferences.getLong(EXPIRES_AT, 0))
    }

    override fun save(token: AuthResult.Success) = preferences.edit {
        putString(TOKEN, token.token)
        putLong(EXPIRES_AT, token.expiresAtMillis)
    }

    override fun clear() = preferences.edit {
        remove(TOKEN)
        remove(EXPIRES_AT)
    }

    override var pendingState: String?
        get() = preferences.getString(PENDING_STATE, null)
        set(value) = preferences.edit { putString(PENDING_STATE, value) }

    private companion object {
        const val FILE_NAME = "geni_auth"
        const val TOKEN = "token"
        const val EXPIRES_AT = "expiresAt"
        const val PENDING_STATE = "pendingState"
    }
}

/** Opens a storage; if that fails, resets it and opens it again. */
internal fun <T> openOrReset(open: () -> T, reset: () -> Unit): T = try {
    open()
} catch (e: Exception) {
    reset()
    open()
}
