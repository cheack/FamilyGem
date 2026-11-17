package app.familygem.geni.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import app.familygem.geni.sync.JobKind
import app.familygem.geni.sync.JobState
import app.familygem.geni.sync.SyncQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/** Logs in to Geni with the OAuth mobile flow and shows the logged-in user. Also receives the OAuth redirect. */
class GeniLoginActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var loginButton: Button
    private lateinit var logoutButton: Button
    private lateinit var createTreeButton: Button
    private lateinit var pendingStatus: TextView
    private lateinit var failedButton: Button
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
        pendingStatus = findViewById(R.id.geni_pending_status)
        failedButton = findViewById(R.id.geni_failed_button)
        failedButton.setOnClickListener { showFailed() }
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
            AlertDialog.Builder(this)
                .setMessage(R.string.geni_logout_confirm)
                .setPositiveButton(R.string.geni_logout) { _, _ ->
                    tokens.clear()
                    showLoggedOut(null)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
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
                Geni.resumeUploads?.invoke() // Uploads stopped for the missing login
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
            } catch (e: IOException) {
                // No connection: the login is kept, only the profile could not be shown
                progress.visibility = View.GONE
                status.setText(R.string.geni_offline)
                logoutButton.visibility = View.VISIBLE
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
                status.text = if (e is IOException) getString(R.string.geni_offline) else e.localizedMessage ?: getString(R.string.geni_login_failed)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Geni.isConfigured) showQueue()
    }

    /** Number of changes waiting for upload, and the button to the failed ones. */
    private fun showQueue() {
        lifecycleScope.launch {
            val jobs = Geni.store(applicationContext).allJobs()
            val pending = jobs.count { it.state == JobState.PENDING || it.state == JobState.SENDING }
            val failed = jobs.count { it.state == JobState.FAILED }
            pendingStatus.text = getString(R.string.geni_pending_changes, pending)
            pendingStatus.visibility = if (pending > 0) View.VISIBLE else View.GONE
            failedButton.text = getString(R.string.geni_failed_changes, failed)
            failedButton.visibility = if (failed > 0) View.VISIBLE else View.GONE
        }
    }

    /** Lists the failed changes with Geni's reason; they can be retried or dismissed together. */
    private fun showFailed() {
        lifecycleScope.launch {
            val failed = Geni.store(applicationContext).allJobs().filter { it.state == JobState.FAILED }
            if (failed.isEmpty()) return@launch showQueue()
            val text = failed.joinToString("\n\n") { "${it.label}\n${it.error.orEmpty()}" }
            AlertDialog.Builder(this@GeniLoginActivity)
                .setTitle(R.string.geni_failed_title)
                .setMessage(text)
                .setPositiveButton(R.string.geni_retry_all) { _, _ ->
                    if (failed.any { it.kind == JobKind.ADD_CHILD || it.kind == JobKind.ADD_PARTNER }) confirmRetryAdds() else retryAll()
                }
                .setNegativeButton(R.string.geni_dismiss_all) { _, _ -> queueAction { dismissFailed() } }
                .setNeutralButton(R.string.geni_close, null)
                .show()
        }
    }

    private fun confirmRetryAdds() {
        AlertDialog.Builder(this)
            .setMessage(R.string.geni_retry_adds_warning)
            .setPositiveButton(R.string.geni_retry_anyway) { _, _ -> retryAll() }
            .setNegativeButton(R.string.geni_close, null)
            .show()
    }

    private fun retryAll() = queueAction {
        retryFailed()
        Geni.resumeUploads?.invoke()
    }

    private fun queueAction(action: suspend SyncQueue.() -> Unit) {
        lifecycleScope.launch {
            Geni.queue(applicationContext).action()
            showQueue()
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
