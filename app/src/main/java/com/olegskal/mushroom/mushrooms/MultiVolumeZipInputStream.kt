package com.olegskal.mushroom.mushrooms

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.PushbackInputStream

/**
 * Потоковий об'єднувач томів .z01 ... .zip в єдиний InputStream для ZipInputStream
 */
internal class MultiVolumeZipInputStream(private val files: List<File>) : InputStream() {
    private var currentIndex = 0
    private var currentStream: InputStream? = null

    init {
        openNextStream()
    }

    private fun openNextStream(): Boolean {
        currentStream?.close()
        currentStream = null
        if (currentIndex >= files.size) return false

        var fis: InputStream = FileInputStream(files[currentIndex])

        // У першому томі (.z01) пропускаємо сигнатуру багатотомника 0x08074B50, якщо вона є
        if (currentIndex == 0) {
            val header = ByteArray(4)
            val read = fis.read(header)
            if (read == 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                header[2] == 0x07.toByte() && header[3] == 0x08.toByte()) {
                // Сигнатуру пропущено
            } else if (read > 0) {
                val pbis = PushbackInputStream(fis, 4)
                pbis.unread(header, 0, read)
                fis = pbis
            }
        }

        currentStream = fis
        currentIndex++
        return true
    }

    override fun read(): Int {
        while (true) {
            val stream = currentStream ?: return -1
            val b = stream.read()
            if (b != -1) return b
            if (!openNextStream()) return -1
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (true) {
            val stream = currentStream ?: return -1
            val r = stream.read(b, off, len)
            if (r != -1) return r
            if (!openNextStream()) return -1
        }
    }

    override fun close() {
        currentStream?.close()
        currentStream = null
    }
}
