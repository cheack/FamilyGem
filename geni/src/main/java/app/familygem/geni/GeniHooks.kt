package app.familygem.geni

import android.app.Activity
import android.content.Intent
import android.view.Menu
import app.familygem.geni.ui.GeniLoginActivity

/** Functions called by one-line GENI-HOOK calls in upstream code. */
object GeniHooks {

    /** Adds the Geni account item to the menu of the trees list. */
    fun addTreesMenu(activity: Activity, menu: Menu) {
        menu.add(R.string.geni_menu_account).setOnMenuItemClickListener {
            activity.startActivity(Intent(activity, GeniLoginActivity::class.java))
            true
        }
    }

    /** Removes the items that would send a Geni tree to a third party or merge it: share (5), merge (6), compare (7). */
    fun trimTreeMenu(treeId: Int, menu: Menu) {
        if (!GeniTrees.isGeni(treeId)) return
        for (id in intArrayOf(5, 6, 7)) menu.removeItem(id)
    }
}
