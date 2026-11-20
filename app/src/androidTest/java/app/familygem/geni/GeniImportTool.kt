package app.familygem.geni

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.familygem.geni.map.GeniParams
import app.familygem.geni.map.ProfileFields
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.folg.gedcom.model.Family
import org.folg.gedcom.model.Gedcom
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import org.folg.gedcom.parser.JsonParser as GedcomJsonParser

/**
 * Developer tool, not a feature: copies a local tree of the installed app into the Geni sandbox account
 * (test data for checks on a device). Skipped unless started with `-e geniImportTree <treeId>`, and only
 * against sandbox.geni.com. Progress goes to logcat (tag GeniImport). The gedcom id to Geni id map is kept in
 * files/geni-import-<treeId>.map, so a run after a failure continues instead of creating duplicates.
 *
 * `adb shell am instrument -w -e class app.familygem.geni.GeniImportTool -e geniImportTree 1
 *  net.cheack.treekin.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class GeniImportTool {

    private val profiles = mutableMapOf<String, String>() // person gedcom id to profile-X
    private val unions = mutableMapOf<String, String>() // family gedcom id to union-X

    @Test
    fun import() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val treeId = args.getString("geniImportTree")
        assumeTrue("no geniImportTree argument", treeId != null)
        check(Geni.config.host == "sandbox.geni.com") { "the tool only talks to the sandbox" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = Geni.client(context)
        val gedcom: Gedcom = GedcomJsonParser().fromJson(File(context.filesDir, "$treeId.json").readText())
        val mapFile = File(context.filesDir, "geni-import-$treeId.map")
        if (mapFile.exists()) mapFile.readLines().forEach {
            val (id, geni) = it.split(' ')
            (if (geni.startsWith("profile-")) profiles else unions)[id] = geni
        }
        fun remember(id: String, geni: String, into: MutableMap<String, String>) {
            into[id] = geni
            mapFile.appendText("$id $geni\n")
        }

        suspend fun create(path: String, personId: String): String {
            val fields = ProfileFields.of(gedcom.getPerson(personId))
            val params = GeniParams.profile(ProfileFields.EMPTY, fields, isNew = true)
            val json = JsonParser.parseString(client.post(path, params)).asJsonObject
            val id = json.get("id").asString
            Log.i("GeniImport", "$personId -> $id ($path)")
            remember(personId, id, profiles)
            // A new profile has the one union its creation made
            val union = json.getAsJsonArray("unions")?.firstOrNull()?.asString?.substringAfterLast('/').orEmpty()
            // Without the union id a later step would create a second union: stop instead
            check(path == "profile/add" || path.endsWith("/add-child") && path.startsWith("union-") || union.startsWith("union-")) {
                "no union in the answer of $path: ${json}"
            }
            return union
        }

        fun partners(f: Family) = f.husbandRefs.orEmpty().map { it.ref } + f.wifeRefs.orEmpty().map { it.ref }
        fun union(path: String) = path.substringAfterLast('/')

        if (profiles.isEmpty()) {
            val root = gedcom.people.first().id
            create("profile/add", root)
        }
        var progress = true
        while (progress) {
            progress = false
            for (f in gedcom.families) {
                val spouses = partners(f)
                val children = f.childRefs.orEmpty().map { it.ref }
                val unionId = unions[f.id]
                if (unionId != null) {
                    for (s in spouses.filter { it !in profiles }) {
                        create("$unionId/add-partner", s); progress = true
                    }
                    for (c in children.filter { it !in profiles }) {
                        create("$unionId/add-child", c); progress = true
                    }
                    continue
                }
                val knownSpouse = spouses.firstOrNull { it in profiles }
                val knownChild = children.firstOrNull { it in profiles }
                if (knownSpouse != null) {
                    val other = spouses.firstOrNull { it !in profiles }
                    val newUnion = if (other != null) create("${profiles[knownSpouse]}/add-partner", other)
                    else if (children.isNotEmpty()) {
                        // Single parent: the first child makes the union
                        val first = children.first()
                        if (first in profiles) "" else create("${profiles[knownSpouse]}/add-child", first)
                    } else ""
                    if (newUnion.isNotEmpty()) {
                        remember(f.id, union(newUnion), unions); progress = true
                    } else if (other != null) progress = true
                } else if (knownChild != null && spouses.isNotEmpty()) {
                    val newUnion = create("${profiles[knownChild]}/add-parent", spouses.first())
                    if (newUnion.isNotEmpty()) remember(f.id, union(newUnion), unions)
                    progress = true
                }
            }
        }
        val missing = gedcom.people.map { it.id }.filter { it !in profiles }
        Log.i("GeniImport", "done: ${profiles.size} profiles, ${unions.size} unions, missing: $missing")
        Unit
    }
}
