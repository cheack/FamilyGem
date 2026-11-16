package app.familygem.geni.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import app.familygem.geni.Geni
import app.familygem.geni.R
import app.familygem.geni.api.GeniAuthRequiredException
import app.familygem.geni.auth.AuthResult
import app.familygem.geni.auth.EncryptedTokenStore
import app.familygem.geni.auth.OAuth
import app.familygem.geni.auth.TokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Logs in to Geni with the OAuth mobile flow and shows the logged-in user. Also receives the OAuth redirect. */
class GeniLoginActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var loginButton: Button
    private lateinit var logoutButton: Button
    private lateinit var tokens: TokenStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.geni_activity_login)
        status = findViewById(R.id.geni_login_status)
        progress = findViewById(R.id.geni_login_progress)
        loginButton = findViewById(R.id.geni_login_button)
        logoutButton = findViewById(R.id.geni_logout_button)
        if (!Geni.isConfigured) {
            status.setText(R.string.geni_not_configured)
            return
        }
        tokens = EncryptedTokenStore(applicationContext)
        loginButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, OAuth.authorizeUrl(Geni.config).toUri()))
        }
        logoutButton.setOnClickListener {
            tokens.clear()
            showLoggedOut(null)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (Geni.isConfigured) handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val redirect = intent.data?.takeIf { intent.action == Intent.ACTION_VIEW }?.toString()
        if (redirect == null) {
            if (tokens.get() != null) loadProfile() else showLoggedOut(null)
            return
        }
        intent.data = null // Not handled again on recreation
        when (val result = OAuth.parseRedirect(redirect, System.currentTimeMillis())) {
            is AuthResult.Success -> {
                tokens.save(result)
                loadProfile()
            }
            is AuthResult.Denied -> showLoggedOut(result.message)
            AuthResult.Invalid -> showLoggedOut(getString(R.string.geni_login_failed))
        }
    }

    private fun loadProfile() {
        progress.visibility = View.VISIBLE
        loginButton.visibility = View.GONE
        logoutButton.visibility = View.GONE
        status.text = null
        lifecycleScope.launch {
            try {
                val profile = Geni.client(applicationContext).currentProfile()
                val name = profile.name ?: listOfNotNull(profile.firstName, profile.lastName).joinToString(" ")
                progress.visibility = View.GONE
                status.text = getString(R.string.geni_logged_in_as, name)
                logoutButton.visibility = View.VISIBLE
            } catch (e: CancellationException) {
                throw e
            } catch (e: GeniAuthRequiredException) {
                showLoggedOut(null)
            } catch (e: Exception) {
                showLoggedOut(e.localizedMessage ?: getString(R.string.geni_login_failed))
            }
        }
    }

    private fun showLoggedOut(message: String?) {
        progress.visibility = View.GONE
        status.text = message
        loginButton.visibility = View.VISIBLE
        logoutButton.visibility = View.GONE
    }
}
