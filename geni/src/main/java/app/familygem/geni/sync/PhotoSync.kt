package app.familygem.geni.sync

import app.familygem.geni.TreeHost
import app.familygem.geni.api.GeniPhoto
import app.familygem.geni.api.GeniProfile
import app.familygem.geni.api.PhotoDownloader
import app.familygem.geni.api.mugshotToPhoto
import app.familygem.geni.map.GeniMapper
import org.folg.gedcom.model.Media
import org.folg.gedcom.model.Person
import java.io.File

/**
 * Keeps the main Geni photo of a person in the FamilyGem tree: downloaded to the tree's media folder and
 * attached to the person as a simple media marked primary and tagged with its Geni photo ID.
 * Upstream's card and diagram then show it like any other media.
 * @param download saves the photo at a URL to a file and returns its content type
 */
class PhotoSync(
    private val store: SyncStore,
    private val host: TreeHost,
    private val download: suspend (String, File) -> String = PhotoDownloader()::download
) {

    /**
     * Brings the main photo of [personGedcomId] in line with [profile]'s mugshot. A photo already downloaded
     * and unchanged (same Geni photo ID) is not fetched again; if the download fails nothing changes.
     * @return true if the tree changed
     */
    suspend fun sync(treeId: Int, profile: GeniProfile, personGedcomId: String): Boolean {
        val photo = mugshotToPhoto(profile)
        val links = store.photoLinks(treeId, profile.id)
        val main = links.firstOrNull { it.isMain }
        if (photo == null) return main?.takeIf { it.state == PhotoState.DOWNLOADED }?.let { remove(treeId, profile, personGedcomId, it) } ?: false
        val url = photo.urlFor(*SIZES) ?: return false
        val dir = host.mediaDir(treeId)
        if (main != null && main.geniPhotoId == photo.id && main.fileName?.let { File(dir, it).isFile } == true) {
            return false
        }
        val tempName = "geni-${photo.id}.download"
        val temp = File(dir, tempName)
        try {
            val type = download(url, temp)
            if (!looksLikeImage(temp)) throw java.io.IOException("Downloaded file is not an image")
            val fileName = "geni-${photo.id}.${extension(type, url)}"
            val target = File(dir, fileName)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
            }
            return attach(treeId, profile, personGedcomId, photo, fileName, main)
        } finally {
            temp.delete()
        }
    }

    private suspend fun attach(treeId: Int, profile: GeniProfile, personGedcomId: String, photo: GeniPhoto, fileName: String, previous: PhotoLink?): Boolean {
        val attached = host.edit(treeId) { gedcom ->
            val person = gedcom.people.orEmpty().firstOrNull { it.id == personGedcomId && GeniMapper.getGeniId(it) == profile.id }
                ?: return@edit false
            removeGeniMedia(person)
            // The Geni photo becomes the primary one: any local primary is demoted
            person.media.orEmpty().forEach { it.primary = null }
            person.addMedia(Media().apply {
                file = fileName
                setFileTag("FILE")
                primary = "Y"
                GeniMapper.setTag(this, PHOTO_TAG, photo.id)
            })
            true
        }
        if (!attached) return false
        previous?.takeIf { it.geniPhotoId != photo.id }?.fileName?.let { File(host.mediaDir(treeId), it).delete() }
        previous?.takeIf { it.geniPhotoId != photo.id }?.let { store.deletePhotoLink(treeId, profile.id, it.geniPhotoId) }
        store.savePhotoLink(PhotoLink(treeId, profile.id, photo.id, null, true, marker(photo), fileName, PhotoState.DOWNLOADED))
        return true
    }

    private suspend fun remove(treeId: Int, profile: GeniProfile, personGedcomId: String, link: PhotoLink): Boolean {
        host.edit(treeId) { gedcom ->
            gedcom.people.orEmpty().firstOrNull { it.id == personGedcomId && GeniMapper.getGeniId(it) == profile.id }?.let { removeGeniMedia(it) }
        }
        link.fileName?.let { File(host.mediaDir(treeId), it).delete() }
        store.deletePhotoLink(treeId, profile.id, link.geniPhotoId)
        return true
    }

    private fun removeGeniMedia(person: Person) {
        val kept = person.media.orEmpty().filter { GeniMapper.getTag(it, PHOTO_TAG) == null }
        person.media = kept.toMutableList()
    }

    companion object {
        /** Custom tag of a media that came from Geni. */
        const val PHOTO_TAG = "_GENI_PHOTO_ID"

        /** Size order: medium is enough for the card and the diagram and keeps downloads small. */
        private val SIZES = arrayOf("medium", "large", "original", "small")

        /** URL path without its signed query: it changes only when the photo does. */
        fun marker(photo: GeniPhoto): String? = photo.urlFor(*SIZES)?.substringBefore('?')

        fun extension(contentType: String, url: String): String = when (contentType.lowercase().substringBefore(';').trim()) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/jpeg", "image/jpg" -> "jpg"
            else -> url.substringBefore('?').substringAfterLast('.', "jpg").takeIf { it.length in 2..4 } ?: "jpg"
        }

        /** True if the file starts like a JPEG, PNG, GIF or WebP image. */
        fun looksLikeImage(file: File): Boolean {
            val head = ByteArray(12)
            val n = file.inputStream().use { it.read(head) }
            if (n < 4) return false
            fun starts(vararg bytes: Int) = bytes.indices.all { head[it] == bytes[it].toByte() }
            return starts(0xFF, 0xD8, 0xFF) || starts(0x89, 0x50, 0x4E, 0x47) || starts(0x47, 0x49, 0x46, 0x38) ||
                    (n >= 12 && starts(0x52, 0x49, 0x46, 0x46) && head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte())
        }
    }
}
