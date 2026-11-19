package app.familygem.geni

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.familygem.geni.sync.AlbumEntry
import com.bumptech.glide.Glide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The album of a Geni person: the photos on Geni, each marked with its state here; tap one to download it. */
object GeniAlbum {

    fun show(activity: Activity, treeId: Int, geniId: String, personGedcomId: String, onChanged: () -> Unit) {
        val owner = activity as? LifecycleOwner ?: return
        val loader = Geni.albumLoader(activity)
        Toast.makeText(activity, R.string.geni_album_loading, Toast.LENGTH_SHORT).show()
        owner.lifecycleScope.launch {
            val entries = try {
                loader.list(treeId, geniId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(activity, activity.getString(R.string.geni_album_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                return@launch
            }
            if (entries.isEmpty()) {
                Toast.makeText(activity, R.string.geni_album_empty, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val rows = entries.toMutableList()
            val adapter = object : ArrayAdapter<AlbumEntry>(activity, 0, rows) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = row(activity, getItem(position)!!)
            }
            val dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.geni_album)
                .setAdapter(adapter, null)
                .setNeutralButton(R.string.geni_album_download_all, null)
                .setNegativeButton(R.string.geni_close, null)
                .create()
            dialog.show()
            dialog.listView.setOnItemClickListener { _, _, position, _ ->
                val entry = rows[position]
                if (entry.downloaded) return@setOnItemClickListener
                owner.lifecycleScope.launch {
                    try {
                        if (loader.download(treeId, geniId, personGedcomId, entry.photo)) {
                            rows[position] = entry.copy(downloaded = true)
                            adapter.notifyDataSetChanged()
                            onChanged()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Toast.makeText(activity, activity.getString(R.string.geni_album_photo_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                owner.lifecycleScope.launch {
                    val added = loader.downloadAll(treeId, geniId, personGedcomId) { photo, e ->
                        Toast.makeText(activity, activity.getString(R.string.geni_album_photo_failed, photo.title ?: photo.id), Toast.LENGTH_SHORT).show()
                    }
                    Toast.makeText(activity, activity.getString(R.string.geni_album_added, added), Toast.LENGTH_SHORT).show()
                    if (added > 0) onChanged()
                    dialog.dismiss()
                }
            }
        }
    }

    private fun row(activity: Activity, entry: AlbumEntry): View {
        val dp = activity.resources.displayMetrics.density
        val image = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams((72 * dp).toInt(), (72 * dp).toInt())
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        entry.photo.urlFor("thumb", "small", "medium")?.let { Glide.with(activity).load(it).into(image) }
        val title = TextView(activity).apply {
            text = entry.photo.title ?: entry.photo.id
            textSize = 16f
        }
        val state = TextView(activity).apply {
            setText(if (entry.downloaded) R.string.geni_album_on_device else R.string.geni_album_tap_to_download)
            textSize = 13f
            alpha = 0.7f
        }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), 0, 0, 0)
            addView(title)
            addView(state)
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (6 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
            addView(image)
            addView(texts)
        }
    }
}
