package app.familygem.geni

import android.app.Activity
import android.util.Log
import android.view.Menu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.familygem.Global
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.sync.LoadPolicy
import app.familygem.geni.sync.LoadResult
import app.familygem.geni.sync.LocalTree
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Person

/** Entry points called by one-line GENI-HOOK calls in upstream code; connects :geni with the app. */
object GeniGlue {

    private var resumed = false

    fun install() {
        if (Geni.host == null) Geni.host = AppTreeHost
        if (!resumed && Geni.isConfigured) {
            resumed = true
            Geni.resumeUploads = { GeniSync.resume(Global.context) }
            GeniSync.resume(Global.context) // Uploads left by an earlier run of the app
            GeniPrefetch.dailyRefresh(Global.context)
        }
        // Registers once; a registration that failed is tried again here
        if (Geni.isConfigured) GeniSync.watchNetwork(Global.context)
    }

    /** Plans Geni uploads after a Geni tree was written. Never breaks the save: errors are only logged. */
    suspend fun onTreeSaved(gedcom: Gedcom, treeId: Int) {
        try {
            install()
            if (!Geni.isConfigured || Geni.store(Global.context).tree(treeId) == null) return
            // Captured on the saving thread, like upstream's own JSON write of the same tree
            GeniSync.onSaved(Global.context, treeId, LocalTree.capture(gedcom))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("GeniSync", "Planning uploads of tree $treeId failed", e)
        }
    }

    fun addTreesMenu(activity: Activity, menu: Menu) {
        install()
        GeniHooks.addTreesMenu(activity, menu)
    }

    private var loginToastShown = false
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Forgets the Geni data of a deleted tree, so a new tree with the same ID does not inherit it. */
    fun onTreeDeleted(treeId: Int) {
        install()
        scope.launch { Geni.store(Global.context).deleteTree(treeId) }
    }

    /** Loads the person's relatives from Geni when the person card opens, if needed; then refreshes the card. */
    fun onPersonOpened(activity: Activity, person: Person?, refresh: () -> Unit) {
        install()
        val geniId = person?.let { GeniMapper.getGeniId(it) } ?: return
        val owner = activity as? LifecycleOwner ?: return
        val treeId = Global.settings.openTree
        owner.lifecycleScope.launch {
            val link = Geni.store(activity).link(treeId, geniId)
            if (!LoadPolicy.shouldLoad(link, System.currentTimeMillis(), LoadPolicy.OPEN_REFRESH_MILLIS)) return@launch
            when (Geni.loader(activity).load(treeId, geniId)) {
                is LoadResult.Loaded -> refresh()
                LoadResult.NeedsLogin -> if (!loginToastShown) {
                    loginToastShown = true
                    Toast.makeText(activity, R.string.geni_login_again, Toast.LENGTH_LONG).show()
                }
                else -> Unit // Offline or Geni error: the cached data stays
            }
        }
    }

    /** Adds "Keep offline" to the menu of a person card of a Geni tree. */
    fun addProfileMenu(activity: Activity, menu: Menu, person: Person?) {
        install()
        val geniId = person?.let { GeniMapper.getGeniId(it) } ?: return
        val treeId = Global.settings.openTree
        menu.add(R.string.geni_keep_offline).setOnMenuItemClickListener {
            val depths = arrayOf("1", "2", "3", "4", "5")
            AlertDialog.Builder(activity)
                .setTitle(R.string.geni_keep_offline_depth)
                .setSingleChoiceItems(depths, 2) { dialog, which ->
                    AppTreeHost.schedulePrefetch(treeId, geniId, which + 1)
                    Toast.makeText(activity, R.string.geni_keep_offline_started, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }.show()
            true
        }
    }
}
