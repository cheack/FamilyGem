package app.familygem.geni

import android.app.Activity
import android.util.Log
import android.view.Menu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.familygem.Global
import android.content.Context
import app.familygem.U
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.sync.DeleteDecision
import app.familygem.geni.sync.DeleteRules
import app.familygem.geni.sync.PersonRemoval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private var treesLoaded = false

    /** Loads the IDs of the Geni trees once, before any hook asks: a hook must never see a Geni tree as a local one. */
    private fun loadTrees() {
        if (treesLoaded) return
        try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) { GeniTrees.load(Geni.store(Global.context)) }
            treesLoaded = true
        } catch (e: Exception) {
            Log.e("GeniSync", "Geni trees not read", e) // Tried again by the next hook
        }
    }

    fun install() {
        loadTrees()
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

    /** True for a Geni tree: its people must not be merged into other trees. */
    fun isGeniTree(treeId: Int): Boolean {
        install()
        return GeniTrees.isGeni(treeId)
    }

    /** Hides share, merge and compare from the menu of a Geni tree. */
    fun trimTreeMenu(treeId: Int, menu: Menu) {
        install()
        GeniHooks.trimTreeMenu(treeId, menu)
    }

    /** In a Geni tree an unlink is refused: Geni's API cannot remove a relationship. True when the caller must not unlink. */
    fun blockUnlink(context: android.content.Context): Boolean {
        install()
        if (!GeniTrees.isGeni(Global.settings.openTree)) return false
        AlertDialog.Builder(context).setMessage(R.string.geni_unlink_blocked).setPositiveButton(android.R.string.ok, null).show()
        return true
    }

    /**
     * Deleting a person in a Geni tree: handled here instead of upstream's delete. Returns true when the caller must stop
     * (a dialog follows); false for a person that is not on Geni, which upstream deletes as usual.
     * [onDone] refreshes the screen after the person was removed from this device.
     */
    fun handleDelete(context: Context, person: Person, onDone: () -> Unit): Boolean {
        install()
        val treeId = Global.settings.openTree
        val geniId = GeniMapper.getGeniId(person)
        if (!GeniTrees.isGeni(treeId) || geniId == null) return false
        val owner = context as? LifecycleOwner ?: return false
        owner.lifecycleScope.launch {
            val store = Geni.store(context)
            val name = U.properName(person)
            when (DeleteRules.decide(true, geniId, store.tree(treeId)?.rootGeniId)) {
                DeleteDecision.Upstream -> Unit
                DeleteDecision.BlockedRoot -> AlertDialog.Builder(context).setMessage(R.string.geni_delete_root)
                    .setPositiveButton(android.R.string.ok, null).show()
                DeleteDecision.Dialog -> {
                    // Managed profiles are listed by guid; offline or on an error the Geni choice is simply not offered
                    val managed = try {
                        DeleteRules.isManaged(store.link(treeId, geniId)?.snapshot, Geni.client(context).managedGuids())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("GeniSync", "Managed profiles not read", e)
                        null
                    }
                    val dialog = AlertDialog.Builder(context).setTitle(name)
                        .setMessage(context.getString(R.string.geni_delete_message) + when (managed) {
                            true -> ""
                            false -> "\n\n" + context.getString(R.string.geni_delete_not_managed)
                            null -> "\n\n" + context.getString(R.string.geni_delete_unchecked)
                        })
                        .setPositiveButton(R.string.geni_delete_local) { _, _ ->
                            owner.lifecycleScope.launch {
                                try {
                                    if (withContext(Dispatchers.IO) { PersonRemoval(store, AppTreeHost).removeFromTree(treeId, geniId) }) onDone()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.e("GeniSync", "Removing $geniId from tree $treeId failed", e)
                                    Toast.makeText(context, R.string.geni_delete_failed, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                    if (managed == true) dialog.setNeutralButton(R.string.geni_delete_geni) { _, _ -> confirmDeleteOnGeni(context, treeId, geniId, name) }
                    dialog.show()
                }
            }
        }
        return true
    }

    private fun confirmDeleteOnGeni(context: Context, treeId: Int, geniId: String, name: String) {
        AlertDialog.Builder(context).setTitle(name).setMessage(R.string.geni_delete_confirm)
            .setPositiveButton(R.string.geni_delete_geni) { _, _ ->
                scope.launch {
                    val queued = try {
                        Geni.queue(context).enqueueDelete(treeId, geniId, name)
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("GeniSync", "Queueing the delete of $geniId failed", e)
                        false
                    }
                    if (queued) GeniSync.kick(context, treeId)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, if (queued) R.string.geni_delete_queued else R.string.geni_delete_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private var loginToastShown = false
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Forgets the Geni data of a deleted tree, so a new tree with the same ID does not inherit it. */
    fun onTreeDeleted(treeId: Int) {
        install()
        GeniTrees.remove(treeId)
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
            when (val result = Geni.loader(activity).load(treeId, geniId)) {
                is LoadResult.Loaded -> refresh()
                is LoadResult.Gone -> if (result.removed) {
                    Toast.makeText(activity, R.string.geni_gone_card, Toast.LENGTH_LONG).show()
                    activity.finish() // The person left the tree
                }
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
