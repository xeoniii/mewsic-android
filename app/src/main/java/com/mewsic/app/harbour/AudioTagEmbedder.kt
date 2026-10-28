package com.mewsic.app.harbour

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure Kotlin audio metadata and album art embedder.
 *
 * Supports:
 *  - MP4 / M4A (ISO Base Media File Format):
 *      Embeds into moov -> udta -> meta -> ilst:
 *        ©nam (Title)
 *        ©ART (Artist)
 *        ©alb (Album)
 *        ©day (Year)
 *        ©lyr (Lyrics)
 *        covr (Cover Art - JPEG or PNG)
 *      Automatically updates chunk offset tables (stco / co64) to preserve flawless playback.
 *
 *  - MP3 (ID3v2.4 / ID3v2.3):
 *      Embeds:
 *        TIT2 (Title)
 *        TPE1 (Artist)
 *        TALB (Album)
 *        TYER / TDRC (Year)
 *        USLT (Lyrics)
 *        APIC (Cover Art - JPEG or PNG)
 */
object AudioTagEmbedder {

    fun embed(
        audioBytes: ByteArray,
        extension: String,
        title: String,
        artist: String,
        album: String,
        year: String,
        genre: String = "",
        lyrics: String? = null,
        coverArtBytes: ByteArray? = null
    ): ByteArray {
        return try {
            when (extension.lowercase()) {
                "m4a", "mp4" -> embedM4a(audioBytes, title, artist, album, year, genre, lyrics, coverArtBytes)
                "mp3"        -> embedMp3(audioBytes, title, artist, album, year, genre, lyrics, coverArtBytes)
                else         -> audioBytes
            }
        } catch (e: Exception) {
            e.printStackTrace()
            audioBytes
        }
    }

    // =========================================================================
    // MP4 / M4A Embedding
    // =========================================================================

    private fun embedM4a(
        bytes: ByteArray,
        title: String,
        artist: String,
        album: String,
        year: String,
        genre: String,
        lyrics: String?,
        coverBytes: ByteArray?
    ): ByteArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        // 1. Locate moov box
        var pos = 0
        var moovOffset = -1
        var moovSize = 0

        while (pos + 8 <= bytes.size) {
            buf.position(pos)
            val size = buf.int.toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also { buf.get(it) }
            val typeStr = String(type, Charsets.US_ASCII)

            if (typeStr == "moov") {
                moovOffset = pos
                moovSize = size.toInt()
                break
            }
            if (size <= 0) break
            pos += size.toInt()
        }

        if (moovOffset == -1 || moovOffset + moovSize > bytes.size) {
            return bytes // No valid moov box found
        }

        val moovData = bytes.copyOfRange(moovOffset + 8, moovOffset + moovSize)

        // 2. Build the new udta atom
        val newUdta = buildUdtaAtom(title, artist, album, year, genre, lyrics, coverBytes)

        // 3. Find existing udta inside moov (if any)
        var mpos = 0
        var oldUdtaOffset = -1
        var oldUdtaSize = 0

        val moovBuf = ByteBuffer.wrap(moovData).order(ByteOrder.BIG_ENDIAN)
        while (mpos + 8 <= moovData.size) {
            moovBuf.position(mpos)
            val size = moovBuf.int.toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also { moovBuf.get(it) }
            val typeStr = String(type, Charsets.US_ASCII)

            if (typeStr == "udta") {
                oldUdtaOffset = mpos
                oldUdtaSize = size.toInt()
                break
            }
            if (size <= 0) break
            mpos += size.toInt()
        }

        val newMoovPayload: ByteArray
        val delta: Int

        if (oldUdtaOffset != -1) {
            // Replace old udta
            val out = ByteArrayOutputStream(moovData.size + newUdta.size - oldUdtaSize)
            out.write(moovData, 0, oldUdtaOffset)
            out.write(newUdta)
            out.write(moovData, oldUdtaOffset + oldUdtaSize, moovData.size - (oldUdtaOffset + oldUdtaSize))
            newMoovPayload = out.toByteArray()
            delta = newUdta.size - oldUdtaSize
        } else {
            // Append new udta to moov
            val out = ByteArrayOutputStream(moovData.size + newUdta.size)
            out.write(moovData)
            out.write(newUdta)
            newMoovPayload = out.toByteArray()
            delta = newUdta.size
        }

        // 4. Update chunk offset tables (stco / co64) by delta
        updateChunkOffsets(newMoovPayload, delta)

        // 5. Construct new moov box
        val newMoovBox = ByteArray(8 + newMoovPayload.size)
        val newMoovBuf = ByteBuffer.wrap(newMoovBox).order(ByteOrder.BIG_ENDIAN)
        newMoovBuf.putInt(newMoovBox.size)
        newMoovBuf.put("moov".toByteArray(Charsets.US_ASCII))
        newMoovBuf.put(newMoovPayload)

        // 6. Assemble the final M4A file
        val finalOut = ByteArrayOutputStream(bytes.size + delta)
        finalOut.write(bytes, 0, moovOffset)
        finalOut.write(newMoovBox)
        finalOut.write(bytes, moovOffset + moovSize, bytes.size - (moovOffset + moovSize))
        return finalOut.toByteArray()
    }

    private fun updateChunkOffsets(buf: ByteArray, delta: Int) {
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
        var pos = 0
        while (pos + 8 <= buf.size) {
            bb.position(pos)
            val size = bb.int.toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also { bb.get(it) }
            val typeStr = String(type, Charsets.US_ASCII)

            if (size <= 0 || pos + size > buf.size) break

            when (typeStr) {
                "stco" -> {
                    bb.position(pos + 12)
                    val count = bb.int
                    var entryPos = pos + 16
                    for (i in 0 until count) {
                        if (entryPos + 4 > buf.size) break
                        bb.position(entryPos)
                        val oldOff = bb.int.toLong() and 0xFFFFFFFFL
                        val newOff = (oldOff + delta).toInt()
                        bb.position(entryPos)
                        bb.putInt(newOff)
                        entryPos += 4
                    }
                }
                "co64" -> {
                    bb.position(pos + 12)
                    val count = bb.int
                    var entryPos = pos + 16
                    for (i in 0 until count) {
                        if (entryPos + 8 > buf.size) break
                        bb.position(entryPos)
                        val oldOff = bb.long
                        val newOff = oldOff + delta
                        bb.position(entryPos)
                        bb.putLong(newOff)
                        entryPos += 8
                    }
                }
                "trak", "mdia", "minf", "stbl" -> {
                    val childBytes = buf.copyOfRange(pos + 8, pos + size.toInt())
                    updateChunkOffsets(childBytes, delta)
                    System.arraycopy(childBytes, 0, buf, pos + 8, childBytes.size)
                }
            }
            pos += size.toInt()
        }
    }

    private fun buildUdtaAtom(
        title: String,
        artist: String,
        album: String,
        year: String,
        genre: String,
        lyrics: String?,
        coverBytes: ByteArray?
    ): ByteArray {
        val ilstOut = ByteArrayOutputStream()

        fun writeTextTag(fourCc: ByteArray, text: String) {
            val payload = text.toByteArray(Charsets.UTF_8)
            val dataBoxSize = 16 + payload.size
            val tagBoxSize = 8 + dataBoxSize

            val bb = ByteBuffer.allocate(tagBoxSize).order(ByteOrder.BIG_ENDIAN)
            bb.putInt(tagBoxSize)
            bb.put(fourCc)
            bb.putInt(dataBoxSize)
            bb.put("data".toByteArray(Charsets.US_ASCII))
            bb.putInt(1) // 1 = UTF-8 text
            bb.putInt(0) // locale
            bb.put(payload)
            ilstOut.write(bb.array())
        }

        if (title.isNotBlank())  writeTextTag(byteArrayOf(0xA9.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte()), title)
        if (artist.isNotBlank()) writeTextTag(byteArrayOf(0xA9.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte()), artist)
        if (album.isNotBlank())  writeTextTag(byteArrayOf(0xA9.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 'b'.code.toByte()), album)
        if (year.isNotBlank())   writeTextTag(byteArrayOf(0xA9.toByte(), 'd'.code.toByte(), 'a'.code.toByte(), 'y'.code.toByte()), year)
        if (genre.isNotBlank())  writeTextTag(byteArrayOf(0xA9.toByte(), 'g'.code.toByte(), 'e'.code.toByte(), 'n'.code.toByte()), genre)
        if (!lyrics.isNullOrBlank()) writeTextTag(byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte()), lyrics)

        if (coverBytes != null && coverBytes.isNotEmpty()) {
            val isPng = coverBytes.size >= 8 && coverBytes[0] == 0x89.toByte() && coverBytes[1] == 0x50.toByte()
            val typeFlag = if (isPng) 14 else 13 // 13 = JPEG, 14 = PNG
            val dataBoxSize = 16 + coverBytes.size
            val tagBoxSize = 8 + dataBoxSize

            val bb = ByteBuffer.allocate(tagBoxSize).order(ByteOrder.BIG_ENDIAN)
            bb.putInt(tagBoxSize)
            bb.put("covr".toByteArray(Charsets.US_ASCII))
            bb.putInt(dataBoxSize)
            bb.put("data".toByteArray(Charsets.US_ASCII))
            bb.putInt(typeFlag)
            bb.putInt(0) // locale
            bb.put(coverBytes)
            ilstOut.write(bb.array())
        }

        val ilstPayload = ilstOut.toByteArray()
        val ilstBox = ByteBuffer.allocate(8 + ilstPayload.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(8 + ilstPayload.size)
            put("ilst".toByteArray(Charsets.US_ASCII))
            put(ilstPayload)
        }.array()

        // Handler box (33 bytes)
        val hdlrBox = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(33)
            put("hdlr".toByteArray(Charsets.US_ASCII))
            putInt(0) // version & flags
            putInt(0) // predefined
            put("mdir".toByteArray(Charsets.US_ASCII))
            put("appl".toByteArray(Charsets.US_ASCII))
            putInt(0)
            putInt(0)
            put(0.toByte())
        }.array()

        // Meta box (FullBox: 12-byte header)
        val metaSize = 12 + hdlrBox.size + ilstBox.size
        val metaBox = ByteBuffer.allocate(metaSize).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(metaSize)
            put("meta".toByteArray(Charsets.US_ASCII))
            putInt(0) // version & flags
            put(hdlrBox)
            put(ilstBox)
        }.array()

        // udta box
        val udtaSize = 8 + metaBox.size
        return ByteBuffer.allocate(udtaSize).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(udtaSize)
            put("udta".toByteArray(Charsets.US_ASCII))
            put(metaBox)
        }.array()
    }

    // =========================================================================
    // MP3 (ID3v2.4) Embedding
    // =========================================================================

    private fun embedMp3(
        bytes: ByteArray,
        title: String,
        artist: String,
        album: String,
        year: String,
        genre: String,
        lyrics: String?,
        coverBytes: ByteArray?
    ): ByteArray {
        val frames = ByteArrayOutputStream()

        fun addTextFrame(frameId: String, text: String) {
            val payload = ByteArrayOutputStream()
            payload.write(3) // 3 = UTF-8 encoding in ID3v2.4
            payload.write(text.toByteArray(Charsets.UTF_8))
            val pBytes = payload.toByteArray()

            val header = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
            header.put(frameId.toByteArray(Charsets.US_ASCII))
            header.putInt(pBytes.size)
            header.putShort(0) // flags
            frames.write(header.array())
            frames.write(pBytes)
        }

        if (title.isNotBlank())  addTextFrame("TIT2", title)
        if (artist.isNotBlank()) addTextFrame("TPE1", artist)
        if (album.isNotBlank())  addTextFrame("TALB", album)
        if (year.isNotBlank()) {
            addTextFrame("TYER", year)
            addTextFrame("TDRC", year)
        }
        if (genre.isNotBlank())  addTextFrame("TCON", genre)

        if (!lyrics.isNullOrBlank()) {
            // USLT: encoding(1) + language(3) + desc(null-term) + lyrics
            val payload = ByteArrayOutputStream()
            payload.write(3) // UTF-8
            payload.write("eng".toByteArray(Charsets.US_ASCII))
            payload.write(0) // empty description null terminator
            payload.write(lyrics.toByteArray(Charsets.UTF_8))
            val pBytes = payload.toByteArray()

            val header = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
            header.put("USLT".toByteArray(Charsets.US_ASCII))
            header.putInt(pBytes.size)
            header.putShort(0)
            frames.write(header.array())
            frames.write(pBytes)
        }

        if (coverBytes != null && coverBytes.isNotEmpty()) {
            val isPng = coverBytes.size >= 8 && coverBytes[0] == 0x89.toByte() && coverBytes[1] == 0x50.toByte()
            val mime = if (isPng) "image/png" else "image/jpeg"

            val payload = ByteArrayOutputStream()
            payload.write(0) // ISO-8859-1 for MIME string
            payload.write(mime.toByteArray(Charsets.US_ASCII))
            payload.write(0) // null-terminated MIME
            payload.write(3) // picture type: 3 = Front Cover
            payload.write(0) // empty description null terminator
            payload.write(coverBytes)
            val pBytes = payload.toByteArray()

            val header = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
            header.put("APIC".toByteArray(Charsets.US_ASCII))
            header.putInt(pBytes.size)
            header.putShort(0)
            frames.write(header.array())
            frames.write(pBytes)
        }

        val frameBytes = frames.toByteArray()
        val tagSize = frameBytes.size

        // ID3 synchsafe size (7 bits per byte)
        val s0 = (tagSize shr 21) and 0x7F
        val s1 = (tagSize shr 14) and 0x7F
        val s2 = (tagSize shr 7) and 0x7F
        val s3 = tagSize and 0x7F

        val id3Header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            4, 0, // version 2.4
            0,    // flags
            s0.toByte(), s1.toByte(), s2.toByte(), s3.toByte()
        )

        // If the original MP3 already had an ID3v2 tag, skip it
        var audioStart = 0
        if (bytes.size >= 10 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            val existingSize = ((bytes[6].toInt() and 0x7F) shl 21) or
                               ((bytes[7].toInt() and 0x7F) shl 14) or
                               ((bytes[8].toInt() and 0x7F) shl 7) or
                               (bytes[9].toInt() and 0x7F)
            audioStart = minOf(10 + existingSize, bytes.size)
        }

        val out = ByteArrayOutputStream(id3Header.size + frameBytes.size + (bytes.size - audioStart))
        out.write(id3Header)
        out.write(frameBytes)
        out.write(bytes, audioStart, bytes.size - audioStart)
        return out.toByteArray()
    }
}
