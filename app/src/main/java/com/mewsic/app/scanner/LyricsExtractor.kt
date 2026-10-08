package com.mewsic.app.scanner

import android.content.Context
import com.mewsic.app.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

object LyricsExtractor {

    private val lyricsCache = HashMap<Long, String>()

    suspend fun getLyrics(context: Context, song: Song): String? = withContext(Dispatchers.IO) {
        synchronized(lyricsCache) {
            lyricsCache[song.id]?.let { return@withContext it }
        }

        var lyrics: String? = null

        // 1. Try sidecar .lrc or .txt file in the same directory as the song
        if (song.filePath.isNotBlank()) {
            try {
                val base = song.filePath.substringBeforeLast('.')
                val lrcFile = File("$base.lrc")
                if (lrcFile.exists() && lrcFile.isFile && lrcFile.length() > 0) {
                    lyrics = cleanLyricsText(lrcFile.readText(Charsets.UTF_8))
                }
                if (lyrics.isNullOrBlank()) {
                    val txtFile = File("$base.txt")
                    if (txtFile.exists() && txtFile.isFile && txtFile.length() > 0) {
                        lyrics = cleanLyricsText(txtFile.readText(Charsets.UTF_8))
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Try parsing embedded lyrics directly from the audio file
        if (lyrics.isNullOrBlank() && song.filePath.isNotBlank()) {
            try {
                val file = File(song.filePath)
                if (file.exists() && file.isFile) {
                    val raw = extractFromFile(file)
                    if (!raw.isNullOrBlank()) {
                        lyrics = cleanLyricsText(raw)
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Try reading stream via contentResolver for scoped storage URIs
        if (lyrics.isNullOrBlank()) {
            try {
                context.contentResolver.openInputStream(song.contentUri)?.use { stream ->
                    val raw = extractFromStream(stream)
                    if (!raw.isNullOrBlank()) {
                        lyrics = cleanLyricsText(raw)
                    }
                }
            } catch (_: Exception) {}
        }

        val finalLyrics = lyrics
        if (!finalLyrics.isNullOrBlank()) {
            synchronized(lyricsCache) {
                lyricsCache[song.id] = finalLyrics
            }
        }

        finalLyrics
    }

    private fun extractFromFile(file: File): String? {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(10)
            if (raf.read(header) < 10) return null

            // Check for ID3v2: 'ID3' (0x49, 0x44, 0x33)
            if (header[0] == 0x49.toByte() && header[1] == 0x44.toByte() && header[2] == 0x33.toByte()) {
                val lyrics = parseId3v2(raf, header)
                if (!lyrics.isNullOrBlank()) return lyrics
            }

            // Check for FLAC: 'fLaC'
            if (header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() &&
                header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte()) {
                val lyrics = parseFlac(raf)
                if (!lyrics.isNullOrBlank()) return lyrics
            }

            // Check for MP4/M4A
            raf.seek(4)
            val ftyp = ByteArray(4)
            if (raf.read(ftyp) == 4 && String(ftyp, Charsets.US_ASCII) == "ftyp") {
                val lyrics = parseMp4(raf)
                if (!lyrics.isNullOrBlank()) return lyrics
            }

            // Check end of file for Lyrics3v2
            val lyrics3 = parseLyrics3(raf)
            if (!lyrics3.isNullOrBlank()) return lyrics3
        }
        return null
    }

    private fun extractFromStream(stream: InputStream): String? {
        val initialBytes = ByteArray(10)
        var readTotal = 0
        while (readTotal < 10) {
            val r = stream.read(initialBytes, readTotal, 10 - readTotal)
            if (r <= 0) break
            readTotal += r
        }
        if (readTotal < 10) return null

        if (initialBytes[0] == 0x49.toByte() && initialBytes[1] == 0x44.toByte() && initialBytes[2] == 0x33.toByte()) {
            val tagSize = ((initialBytes[6].toInt() and 0x7F) shl 21) or
                          ((initialBytes[7].toInt() and 0x7F) shl 14) or
                          ((initialBytes[8].toInt() and 0x7F) shl 7) or
                          (initialBytes[9].toInt() and 0x7F)

            if (tagSize in 1..2_000_000) {
                val fullTag = ByteArray(tagSize)
                var tagRead = 0
                while (tagRead < tagSize) {
                    val count = stream.read(fullTag, tagRead, tagSize - tagRead)
                    if (count <= 0) break
                    tagRead += count
                }
                return parseId3v2FromBytes(initialBytes, fullTag)
            }
        }
        return null
    }

    private fun parseId3v2(raf: RandomAccessFile, header: ByteArray): String? {
        val version = header[3].toInt() and 0xFF
        val flags = header[5].toInt() and 0xFF
        val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                      ((header[7].toInt() and 0x7F) shl 14) or
                      ((header[8].toInt() and 0x7F) shl 7) or
                      (header[9].toInt() and 0x7F)

        val tagEnd = 10L + tagSize
        var currentPos = 10L

        // Skip extended header if present
        if ((flags and 0x40) != 0) {
            val extHeaderSize = if (version == 4) {
                val b = ByteArray(4)
                if (raf.read(b) == 4) {
                    ((b[0].toInt() and 0x7F) shl 21) or
                    ((b[1].toInt() and 0x7F) shl 14) or
                    ((b[2].toInt() and 0x7F) shl 7) or
                    (b[3].toInt() and 0x7F)
                } else 0
            } else {
                raf.readInt()
            }
            if (extHeaderSize > 0) {
                currentPos += if (version == 4) extHeaderSize.toLong() else (4L + extHeaderSize)
                raf.seek(currentPos)
            }
        }

        while (currentPos < tagEnd - 10) {
            raf.seek(currentPos)
            if (version == 2) {
                val idBytes = ByteArray(3)
                if (raf.read(idBytes) < 3 || idBytes[0] == 0.toByte()) break
                val frameId = String(idBytes, Charsets.US_ASCII)
                val s0 = raf.read()
                val s1 = raf.read()
                val s2 = raf.read()
                if (s0 < 0 || s1 < 0 || s2 < 0) break
                val frameSize = (s0 shl 16) or (s1 shl 8) or s2
                currentPos += 6 + frameSize
                if (frameSize <= 0 || currentPos > tagEnd) break

                if (frameId == "ULT") {
                    val data = ByteArray(frameSize)
                    raf.readFully(data)
                    return parseUsltPayload(data)
                }
            } else {
                val idBytes = ByteArray(4)
                if (raf.read(idBytes) < 4 || idBytes[0] == 0.toByte()) break
                val frameId = String(idBytes, Charsets.US_ASCII)
                val b = ByteArray(4)
                if (raf.read(b) < 4) break

                val frameSize = if (version == 4) {
                    ((b[0].toInt() and 0x7F) shl 21) or
                    ((b[1].toInt() and 0x7F) shl 14) or
                    ((b[2].toInt() and 0x7F) shl 7) or
                    (b[3].toInt() and 0x7F)
                } else {
                    ((b[0].toInt() and 0xFF) shl 24) or
                    ((b[1].toInt() and 0xFF) shl 16) or
                    ((b[2].toInt() and 0xFF) shl 8) or
                    (b[3].toInt() and 0xFF)
                }

                raf.skipBytes(2) // 2 flags bytes
                val payloadStart = currentPos + 10
                currentPos += 10 + frameSize
                if (frameSize <= 0 || currentPos > tagEnd + 10) break

                if (frameId == "USLT") {
                    val data = ByteArray(frameSize)
                    raf.seek(payloadStart)
                    raf.readFully(data)
                    val result = parseUsltPayload(data)
                    if (!result.isNullOrBlank()) return result
                } else if (frameId == "SYLT") {
                    val data = ByteArray(frameSize)
                    raf.seek(payloadStart)
                    raf.readFully(data)
                    val result = parseSyltPayload(data)
                    if (!result.isNullOrBlank()) return result
                } else if (frameId == "TXXX") {
                    val data = ByteArray(frameSize)
                    raf.seek(payloadStart)
                    raf.readFully(data)
                    val result = parseTxxxLyrics(data)
                    if (!result.isNullOrBlank()) return result
                }
            }
        }
        return null
    }

    private fun parseId3v2FromBytes(header: ByteArray, tagData: ByteArray): String? {
        val version = header[3].toInt() and 0xFF
        var offset = 0
        val tagLength = tagData.size

        while (offset < tagLength - 10) {
            val frameId = String(tagData, offset, 4, Charsets.US_ASCII)
            if (tagData[offset] == 0.toByte()) break

            val frameSize = if (version == 4) {
                ((tagData[offset + 4].toInt() and 0x7F) shl 21) or
                ((tagData[offset + 5].toInt() and 0x7F) shl 14) or
                ((tagData[offset + 6].toInt() and 0x7F) shl 7) or
                (tagData[offset + 7].toInt() and 0x7F)
            } else {
                ((tagData[offset + 4].toInt() and 0xFF) shl 24) or
                ((tagData[offset + 5].toInt() and 0xFF) shl 16) or
                ((tagData[offset + 6].toInt() and 0xFF) shl 8) or
                (tagData[offset + 7].toInt() and 0xFF)
            }

            val payloadStart = offset + 10
            offset += 10 + frameSize
            if (frameSize <= 0 || offset > tagLength) break

            if (frameId == "USLT") {
                val data = tagData.copyOfRange(payloadStart, payloadStart + frameSize)
                val result = parseUsltPayload(data)
                if (!result.isNullOrBlank()) return result
            } else if (frameId == "SYLT") {
                val data = tagData.copyOfRange(payloadStart, payloadStart + frameSize)
                val result = parseSyltPayload(data)
                if (!result.isNullOrBlank()) return result
            } else if (frameId == "TXXX") {
                val data = tagData.copyOfRange(payloadStart, payloadStart + frameSize)
                val result = parseTxxxLyrics(data)
                if (!result.isNullOrBlank()) return result
            }
        }
        return null
    }

    private fun parseUsltPayload(data: ByteArray): String? {
        if (data.size < 5) return null
        val encodingByte = data[0].toInt() and 0xFF
        val charset = when (encodingByte) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }

        // Language is at 1..3. Descriptor starts at offset 4.
        var textOffset = 4
        if (encodingByte == 1 || encodingByte == 2) {
            while (textOffset + 1 < data.size) {
                if (data[textOffset] == 0.toByte() && data[textOffset + 1] == 0.toByte()) {
                    textOffset += 2
                    break
                }
                textOffset += 2
            }
        } else {
            while (textOffset < data.size) {
                if (data[textOffset] == 0.toByte()) {
                    textOffset += 1
                    break
                }
                textOffset += 1
            }
        }

        if (textOffset >= data.size) return null
        val length = data.size - textOffset
        val lyrics = String(data, textOffset, length, charset).trim()
        return lyrics.ifBlank { null }
    }

    private fun parseSyltPayload(data: ByteArray): String? {
        if (data.size < 6) return null
        val encodingByte = data[0].toInt() and 0xFF
        val charset = when (encodingByte) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }

        var offset = 6
        if (encodingByte == 1 || encodingByte == 2) {
            while (offset + 1 < data.size) {
                if (data[offset] == 0.toByte() && data[offset + 1] == 0.toByte()) {
                    offset += 2
                    break
                }
                offset += 2
            }
        } else {
            while (offset < data.size) {
                if (data[offset] == 0.toByte()) {
                    offset += 1
                    break
                }
                offset += 1
            }
        }

        val sb = StringBuilder()
        while (offset < data.size) {
            val textStart = offset
            if (encodingByte == 1 || encodingByte == 2) {
                while (offset + 1 < data.size && !(data[offset] == 0.toByte() && data[offset + 1] == 0.toByte())) {
                    offset += 2
                }
                if (offset > textStart) {
                    val line = String(data, textStart, offset - textStart, charset).trim()
                    if (line.isNotBlank()) sb.append(line).append("\n")
                }
                offset += 2
            } else {
                while (offset < data.size && data[offset] != 0.toByte()) {
                    offset++
                }
                if (offset > textStart) {
                    val line = String(data, textStart, offset - textStart, charset).trim()
                    if (line.isNotBlank()) sb.append(line).append("\n")
                }
                offset++
            }
            offset += 4 // skip 4-byte timestamp
        }

        return sb.toString().trim().ifBlank { null }
    }

    private fun parseTxxxLyrics(data: ByteArray): String? {
        if (data.size < 3) return null
        val encodingByte = data[0].toInt() and 0xFF
        val charset = when (encodingByte) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        var descEnd = 1
        if (encodingByte == 1 || encodingByte == 2) {
            while (descEnd + 1 < data.size && !(data[descEnd] == 0.toByte() && data[descEnd + 1] == 0.toByte())) {
                descEnd += 2
            }
            val desc = String(data, 1, descEnd - 1, charset).trim()
            descEnd += 2
            if (desc.equals("LYRICS", true) || desc.equals("UNSYNCEDLYRICS", true)) {
                if (descEnd < data.size) {
                    return String(data, descEnd, data.size - descEnd, charset).trim()
                }
            }
        } else {
            while (descEnd < data.size && data[descEnd] != 0.toByte()) {
                descEnd++
            }
            val desc = String(data, 1, descEnd - 1, charset).trim()
            descEnd++
            if (desc.equals("LYRICS", true) || desc.equals("UNSYNCEDLYRICS", true)) {
                if (descEnd < data.size) {
                    return String(data, descEnd, data.size - descEnd, charset).trim()
                }
            }
        }
        return null
    }

    private fun parseFlac(raf: RandomAccessFile): String? {
        raf.seek(4) // after 'fLaC'
        var isLast = false
        while (!isLast) {
            val headerByte = raf.read()
            if (headerByte < 0) break
            isLast = (headerByte and 0x80) != 0
            val blockType = headerByte and 0x7F

            val b0 = raf.read()
            val b1 = raf.read()
            val b2 = raf.read()
            if (b0 < 0 || b1 < 0 || b2 < 0) break
            val length = (b0 shl 16) or (b1 shl 8) or b2

            if (blockType == 4) { // VORBIS_COMMENT
                val data = ByteArray(length)
                raf.readFully(data)
                return parseVorbisComment(data)
            } else {
                raf.skipBytes(length)
            }
        }
        return null
    }

    private fun parseVorbisComment(data: ByteArray): String? {
        if (data.size < 8) return null
        var offset = 0

        // Vendor string length (32-bit Little Endian)
        val vendorLength = (data[offset].toInt() and 0xFF) or
                          ((data[offset + 1].toInt() and 0xFF) shl 8) or
                          ((data[offset + 2].toInt() and 0xFF) shl 16) or
                          ((data[offset + 3].toInt() and 0xFF) shl 24)
        offset += 4 + vendorLength
        if (offset + 4 > data.size) return null

        // User comment list length (32-bit LE)
        val count = (data[offset].toInt() and 0xFF) or
                    ((data[offset + 1].toInt() and 0xFF) shl 8) or
                    ((data[offset + 2].toInt() and 0xFF) shl 16) or
                    ((data[offset + 3].toInt() and 0xFF) shl 24)
        offset += 4

        for (i in 0 until count) {
            if (offset + 4 > data.size) break
            val commentLen = (data[offset].toInt() and 0xFF) or
                             ((data[offset + 1].toInt() and 0xFF) shl 8) or
                             ((data[offset + 2].toInt() and 0xFF) shl 16) or
                             ((data[offset + 3].toInt() and 0xFF) shl 24)
            offset += 4
            if (offset + commentLen > data.size || commentLen <= 0) break

            val comment = String(data, offset, commentLen, Charsets.UTF_8)
            offset += commentLen

            if (comment.startsWith("LYRICS=", true) ||
                comment.startsWith("UNSYNCEDLYRICS=", true) ||
                comment.startsWith("SYNCEDLYRICS=", true)) {
                return comment.substringAfter('=').trim()
            }
        }
        return null
    }

    private fun parseMp4(raf: RandomAccessFile): String? {
        // Search for '©lyr' atom across file (atoms usually within first 500KB)
        val maxSearch = minOf(raf.length(), 500_000L)
        val buffer = ByteArray(maxSearch.toInt())
        raf.seek(0)
        val read = raf.read(buffer)
        if (read <= 0) return null

        // Find byte signature for '©lyr': 0xA9, 0x6C, 0x79, 0x72
        val target = byteArrayOf(0xA9.toByte(), 0x6C.toByte(), 0x79.toByte(), 0x72.toByte())
        var idx = 0
        while (idx < read - 20) {
            if (buffer[idx] == target[0] && buffer[idx + 1] == target[1] &&
                buffer[idx + 2] == target[2] && buffer[idx + 3] == target[3]) {
                // Inside ©lyr atom, look for 'data' atom
                var sub = idx + 4
                while (sub < minOf(idx + 500, read - 12)) {
                    if (buffer[sub + 4] == 'd'.code.toByte() && buffer[sub + 5] == 'a'.code.toByte() &&
                        buffer[sub + 6] == 't'.code.toByte() && buffer[sub + 7] == 'a'.code.toByte()) {
                        val dataLen = ((buffer[sub].toInt() and 0xFF) shl 24) or
                                      ((buffer[sub + 1].toInt() and 0xFF) shl 16) or
                                      ((buffer[sub + 2].toInt() and 0xFF) shl 8) or
                                      (buffer[sub + 3].toInt() and 0xFF)
                        val textStart = sub + 16 // skip data atom header + 8 flag bytes
                        val textLen = dataLen - 16
                        if (textLen > 0 && textStart + textLen <= read) {
                            return String(buffer, textStart, textLen, Charsets.UTF_8).trim()
                        }
                    }
                    sub++
                }
            }
            idx++
        }
        return null
    }

    private fun parseLyrics3(raf: RandomAccessFile): String? {
        val fileLen = raf.length()
        if (fileLen < 500) return null
        val searchLen = minOf(fileLen, 16384L).toInt()
        raf.seek(fileLen - searchLen)
        val buffer = ByteArray(searchLen)
        val count = raf.read(buffer)
        if (count < 20) return null

        val str = String(buffer, Charsets.ISO_8859_1)
        val beginIdx = str.indexOf("LYRICSBEGIN")
        val endIdx = str.indexOf("LYRICS200")
        if (beginIdx != -1 && endIdx != -1 && beginIdx < endIdx) {
            val content = str.substring(beginIdx + 11, endIdx)
            // Fields are in format ID (3 chars), Length (5 digits), Value
            var pos = 0
            while (pos + 8 < content.length) {
                val fieldId = content.substring(pos, pos + 3)
                val lenStr = content.substring(pos + 3, pos + 8)
                val len = lenStr.toIntOrNull() ?: break
                pos += 8
                if (pos + len <= content.length) {
                    if (fieldId == "LYR") {
                        return content.substring(pos, pos + len).trim()
                    }
                    pos += len
                } else break
            }
        }
        return null
    }

    fun cleanLyricsText(raw: String): String {
        val normalized = raw.replace("\r\n", "\n").replace("\r", "\n")
        val lrcRegex = Regex("""^\[\d{1,2}:\d{2}(?:\.\d{1,3})?\]\s*""")
        val headerRegex = Regex("""^\[(ti|ar|al|by|offset|length|re|ve):.*\]$""", RegexOption.IGNORE_CASE)

        val lines = normalized.lines().mapNotNull { line ->
            var cleaned = line.trim()
            if (headerRegex.matches(cleaned)) return@mapNotNull null
            while (lrcRegex.containsMatchIn(cleaned)) {
                cleaned = cleaned.replace(lrcRegex, "").trim()
            }
            cleaned
        }

        return lines.joinToString("\n").trim()
    }
}
