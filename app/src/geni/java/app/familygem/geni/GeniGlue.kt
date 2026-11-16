package app.familygem.geni

import android.app.Activity
import android.view.Menu
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.familygem.Global
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.sync.LoadPolicy
import app.familygem.geni.sync.LoadResult
import kotlinx.coroutines.launch
import org.folg.gedcom.model.Person

/** Entry points called by one-line GENI-HOOK calls in upstream code; connects :geni with the app. */
object GeniGlue {

    fun install() {
        if (Geni.host == null) Geni.host = AppTreeHost
    }

    fun addTreesMenu(activity: Activity, menu: Menu) {
        install()
        GeniHooks.addTreesMenu(activity, menu)
    }

    private var loginToastShown = false

    /** Loads the person's relatives from Geni when the person card opens, if needed; then refreshes the card. */
    fun onPersonOpened(activity: Activity, person: Person?, refresh: () -> Unit) {
        install()
        val geniId = person?.let { GeniMapper.getGeniId(it) } ?: return
        val owner = activity as? LifecycleOwner ?: return
        val treeId = Global.settings.openTree
        owner.lifecycleScope.launch {
            val link = Geni.store(activity).link(treeId, geniId)
            if (!LoadPolicy.shouldLoad(link, System.currentTimeMillis())) return@launch
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
}
