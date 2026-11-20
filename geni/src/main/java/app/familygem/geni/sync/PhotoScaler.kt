package app.familygem.geni.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Shrinks big photos before they are sent to Geni. */
object PhotoScaler {

    private const val QUALITY = 85

    /**
     * The bytes to upload for [file]: a JPEG whose longer side is at most [maxSide] is returned as it is,
     * anything else is decoded, scaled down if needed and encoded as JPEG.
     */
    suspend fun scaled(file: File, maxSide: Int = 2048): ByteArray = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) throw IOException("Not an image: ${file.name}")
        val longer = maxOf(width, height)
        val orientation = try {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val upright = orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED
        if (longer <= maxSide && bounds.outMimeType == "image/jpeg" && upright) return@withContext file.readBytes()
        var sample = 1
        while (longer / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Cannot decode: ${file.name}")
        val factor = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
        val scaled = if (factor < 1f) Bitmap.createScaledBitmap(decoded, maxOf(1, (decoded.width * factor).toInt()),
            maxOf(1, (decoded.height * factor).toInt()), true).also { if (it !== decoded) decoded.recycle() } else decoded
        val bitmap = upright(scaled, orientation)
        try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** The bitmap turned or flipped as its EXIF [orientation] says; the given one is recycled when a new one is made. */
    private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }
}
