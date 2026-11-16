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
}

/** Token store in encrypted shared preferences. */
@Suppress("DEPRECATION") // security-crypto has no replacement yet; the app module uses it too
class EncryptedTokenStore(context: Context) : TokenStore {

    private val preferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "geni_auth",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override fun get(): AuthResult.Success? {
        val token = preferences.getString(TOKEN, null) ?: return null
        return AuthResult.Success(token, preferences.getLong(EXPIRES_AT, 0))
    }

    override fun save(token: AuthResult.Success) = preferences.edit {
        putString(TOKEN, token.token)
        putLong(EXPIRES_AT, token.expiresAtMillis)
    }

    override fun clear() = preferences.edit { clear() }

    private companion object {
        const val TOKEN = "token"
        const val EXPIRES_AT = "expiresAt"
    }
}
