package app.familygem.geni.debug

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AppCompatActivity
import app.familygem.geni.Geni
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug builds only. Saves the raw JSON of a Geni API GET request, for exploring the API:
 * adb shell am start -n app.familygem/app.familygem.geni.debug.GeniDumpActivity --es path profile --es name me
 * Output: files/geni-dump/<name>.json (or <name>.error.txt), read with
 * adb shell run-as app.familygem cat files/geni-dump/<name>.json
 * The token is never written.
 */
class GeniDumpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path")
        if (path == null) {
            finishWith("Missing extra 'path'")
            return
        }
        val name = intent.getStringExtra("name") ?: path.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val dir = File(filesDir, "geni-dump").apply { mkdirs() }
        lifecycleScope.launch {
            try {
                File(dir, "$name.json").writeText(Geni.client(applicationContext).getJson(path))
                finishWith("Saved $name.json")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                File(dir, "$name.error.txt").writeText("${e.javaClass.simpleName}: ${e.message}")
                finishWith("Error saved to $name.error.txt")
            }
        }
    }

    private fun finishWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK)
        finish()
    }
}
