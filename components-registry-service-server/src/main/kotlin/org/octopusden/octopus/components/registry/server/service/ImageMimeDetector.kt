package org.octopusden.octopus.components.registry.server.service

/**
 * SYS-062: derive an image MIME type from a byte payload's MAGIC NUMBER, never from a
 * client-supplied `Content-Type`. Only the formats we accept for screenshots are
 * recognized; everything else returns null so the caller rejects the upload.
 *
 * The normalized MIME returned here is what gets stored and later echoed on the
 * attachment-bytes response (with `X-Content-Type-Options: nosniff`), so a mislabeled
 * or non-image payload can never be served back as an executable/HTML type.
 */
object ImageMimeDetector {
    const val PNG = "image/png"
    const val JPEG = "image/jpeg"

    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    /** The normalized MIME for [bytes], or null if it is not an accepted image format. */
    fun detect(bytes: ByteArray): String? =
        when {
            startsWith(bytes, PNG_MAGIC) -> PNG
            startsWith(bytes, JPEG_MAGIC) -> JPEG
            else -> null
        }

    private fun startsWith(
        bytes: ByteArray,
        magic: ByteArray,
    ): Boolean {
        if (bytes.size < magic.size) return false
        for (i in magic.indices) {
            if (bytes[i] != magic[i]) return false
        }
        return true
    }
}
