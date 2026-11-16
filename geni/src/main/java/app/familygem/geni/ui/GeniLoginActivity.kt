package app.familygem.geni.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
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
    private lateinit var createTreeButton: Button
    private lateinit var tokens: TokenStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.geni_activity_login)
        setSupportActionBar(findViewById<Toolbar>(R.id.geni_login_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        status = findViewById(R.id.geni_login_status)
        progress = findViewById(R.id.geni_login_progress)
        loginButton = findViewById(R.id.geni_login_button)
        logoutButton = findViewById(R.id.geni_logout_button)
        createTreeButton = findViewById(R.id.geni_create_tree_button)
        if (!Geni.isConfigured) {
            status.setText(R.string.geni_not_configured)
            return
        }
        tokens = EncryptedTokenStore(applicationContext)
        loginButton.setOnClickListener {
            val state = OAuth.newState()
            tokens.pendingState = state
            startActivity(Intent(Intent.ACTION_VIEW, OAuth.authorizeUrl(Geni.config, state).toUri()))
        }
        createTreeButton.setOnClickListener { createTree() }
        logoutButton.setOnClickListener {
            tokens.clear()
            showLoggedOut(null)
        }
        // On recreation Android gives back the original launch intent, redirect included: it must not be handled again
        if (savedInstanceState == null) handleIntent(intent) else showSavedState()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun showSavedState() {
        if (tokens.get() != null) loadProfile() else showLoggedOut(null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (Geni.isConfigured) handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val redirect = intent.data?.takeIf { intent.action == Intent.ACTION_VIEW }?.toString()
        if (redirect == null) {
            showSavedState()
            return
        }
        // The pending state is used only once, so a redirect replayed later (e.g. after process death) is rejected
        val expectedState = tokens.pendingState
        tokens.pendingState = null
        when (val result = OAuth.parseRedirect(redirect, System.currentTimeMillis(), expectedState)) {
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
                val canCreate = Geni.host != null && Geni.loader(applicationContext).existingTree(profile.id) == null
                createTreeButton.visibility = if (canCreate) View.VISIBLE else View.GONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: GeniAuthRequiredException) {
                showLoggedOut(null)
            } catch (e: Exception) {
                showLoggedOut(e.localizedMessage ?: getString(R.string.geni_login_failed))
            }
        }
    }

    private fun createTree() {
        createTreeButton.isEnabled = false
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                Geni.loader(applicationContext).createTree()
                Toast.makeText(this@GeniLoginActivity, R.string.geni_tree_created, Toast.LENGTH_LONG).show()
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: GeniAuthRequiredException) {
                showLoggedOut(null)
            } catch (e: Exception) {
                progress.visibility = View.GONE
                createTreeButton.isEnabled = true
                status.text = e.localizedMessage ?: getString(R.string.geni_login_failed)
            }
        }
    }

    private fun showLoggedOut(message: String?) {
        createTreeButton.visibility = View.GONE
        progress.visibility = View.GONE
        status.text = message
        loginButton.visibility = View.VISIBLE
        logoutButton.visibility = View.GONE
    }
}
