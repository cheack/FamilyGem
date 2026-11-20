package app.familygem.geni

import android.content.Context

/** Local settings of the Geni integration (plain preferences: nothing secret). */
object GeniSettings {

    private const val FILE = "geni_settings"
    private const val UPLOAD_PHOTOS = "upload_photos"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** True (the default) to send new photos of Geni people to Geni. */
    fun uploadPhotos(context: Context): Boolean = prefs(context).getBoolean(UPLOAD_PHOTOS, true)

    fun setUploadPhotos(context: Context, value: Boolean) = prefs(context).edit().putBoolean(UPLOAD_PHOTOS, value).apply()
}
