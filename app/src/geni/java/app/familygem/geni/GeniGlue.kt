package app.familygem.geni

import android.app.Activity
import android.view.Menu

/** Entry points called by one-line GENI-HOOK calls in upstream code; connects :geni with the app. */
object GeniGlue {

    fun install() {
        if (Geni.host == null) Geni.host = AppTreeHost
    }

    fun addTreesMenu(activity: Activity, menu: Menu) {
        install()
        GeniHooks.addTreesMenu(activity, menu)
    }
}
