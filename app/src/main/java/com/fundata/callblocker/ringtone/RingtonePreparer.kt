package com.fundata.callblocker.ringtone

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.RingtoneManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.fundata.callblocker.data.DiagnosticsStore
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object RingtonePreparer {

    private const val PREF = "ringtone_delay"
    private const val KEY_ORIGINAL_URI = "original_uri"
    private const val KEY_DELAYED_URI = "delayed_uri"
    private const val FILE_NAME = "CallBlocker_Delayed.wav"
    private const val FILE_PREFIX = "CallBlocker_Delayed"
    private const val RELATIVE_DIR = "Ringtones/CallLabelBlocker/"

    data class Result(
        val delayedUri: Uri,
        val originalUri: Uri,
        val sampleRate: Int,
        val channels: Int,
        val delayMs: Int,
        val created: Boolean
    )

    fun prepare(context: Context, delayMs: Int = 1000): Result {
        require(delayMs in 100..10000)

        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val currentUri = RingtoneManager.getActualDefaultRingtoneUri(
            context,
            RingtoneManager.TYPE_RINGTONE
        )
        val existingUri = findExistingDelayedRingtone(context)
        if (existingUri != null) {
            val editor = prefs.edit().putString(KEY_DELAYED_URI, existingUri.toString())
            if (currentUri != null && !isOurGeneratedRingtone(context, currentUri, existingUri)) {
                editor.putString(KEY_ORIGINAL_URI, currentUri.toString())
            }
            editor.apply()
            return Result(
                delayedUri = existingUri,
                originalUri = currentUri ?: existingUri,
                sampleRate = 0,
                channels = 0,
                delayMs = delayMs,
                created = false
            )
        }

        val selectedUri = currentUri ?: error("No system ringtone is selected")
        val savedOriginal = prefs.getString(KEY_ORIGINAL_URI, null)?.let(Uri::parse)
        val savedDelayed = prefs.getString(KEY_DELAYED_URI, null)?.let(Uri::parse)
        val originalUri = if (isOurGeneratedRingtone(context, selectedUri, savedDelayed)) {
            savedOriginal ?: error("Select the original ringtone before creating a delayed copy")
        } else {
            selectedUri
        }
        val highlightOffsetMs = readHighlightOffset(originalUri)
        val sourceUri = withoutHighlightOffset(originalUri, highlightOffsetMs)
        val decoded = decodeToPcm16(context, sourceUri)
        val pcmOffset = if (highlightOffsetMs > 0L) {
            pcmOffsetFromMillis(
                decoded.pcm16,
                decoded.sampleRate,
                decoded.channels,
                highlightOffsetMs
            )
        } else {
            0
        }
        deleteGeneratedRingtones(context)
        val delayedUri = createRingtone(
            context = context,
            pcm16 = decoded.pcm16,
            pcmOffset = pcmOffset,
            sampleRate = decoded.sampleRate,
            channels = decoded.channels,
            delayMs = delayMs
        )
        prefs.edit()
            .putString(KEY_ORIGINAL_URI, originalUri.toString())
            .putString(KEY_DELAYED_URI, delayedUri.toString())
            .apply()

        return Result(
            delayedUri = delayedUri,
            originalUri = originalUri,
            sampleRate = decoded.sampleRate,
            channels = decoded.channels,
            delayMs = delayMs,
            created = true
        )
    }

    fun isDelayedRingtoneActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val current = RingtoneManager.getActualDefaultRingtoneUri(
            context,
            RingtoneManager.TYPE_RINGTONE
        ) ?: return false
        val saved = prefs.getString(KEY_DELAYED_URI, null)?.let(Uri::parse)
        return isOurGeneratedRingtone(context, current, saved)
    }

    fun originalUri(context: Context): Uri? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_ORIGINAL_URI, null)
            ?.let(Uri::parse)

    private fun readHighlightOffset(uri: Uri): Long =
        uri.getQueryParameter("highlight_offset")
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: 0L

    private fun withoutHighlightOffset(uri: Uri, highlightOffsetMs: Long): Uri =
        if (highlightOffsetMs > 0L) removeQueryParameter(uri, "highlight_offset") else uri

    private fun findExistingDelayedRingtone(context: Context): Uri? {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return try {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND " +
                    "${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
                arrayOf("$RELATIVE_DIR%", FILE_NAME),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else Uri.withAppendedPath(collection, cursor.getLong(0).toString())
            }
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Ringtone: find existing delayed ringtone", t)
            null
        }
    }

    private fun createRingtone(
        context: Context,
        pcm16: ByteArray,
        pcmOffset: Int,
        sampleRate: Int,
        channels: Int,
        delayMs: Int
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Audio.Media.TITLE, FILE_NAME.removeSuffix(".wav"))
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Audio.Media.IS_RINGTONE, 1)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, 0)
            put(MediaStore.Audio.Media.IS_ALARM, 0)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val newUri = resolver.insert(collection, values)
            ?: error("Failed to create a ringtone in MediaStore")

        try {
            resolver.openOutputStream(newUri, "w")?.use { stream ->
                BufferedOutputStream(stream).use { output ->
                    writeDelayedWav(
                        output = output,
                        pcm16 = pcm16,
                        pcmOffset = pcmOffset,
                        sampleRate = sampleRate,
                        channels = channels,
                        delayMs = delayMs
                    )
                }
            } ?: error("Failed to open the new ringtone for writing")

            resolver.update(
                newUri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null,
                null
            )
            return newUri
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Ringtone: create file", t)
            try {
                resolver.delete(newUri, null, null)
            } catch (deleteError: Throwable) {
                DiagnosticsStore.saveError("Ringtone: delete incomplete file", deleteError)
            }
            throw t
        }
    }

    private fun deleteGeneratedRingtones(context: Context) {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        try {
            resolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME),
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("$RELATIVE_DIR%", "$FILE_PREFIX%"),
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val uris = ArrayList<Uri>()
                while (cursor.moveToNext()) {
                    uris += Uri.withAppendedPath(collection, cursor.getLong(idIndex).toString())
                }
                for (uri in uris) {
                    try {
                        resolver.delete(uri, null, null)
                    } catch (t: Throwable) {
                        DiagnosticsStore.saveError("Ringtone: delete old file $uri", t)
                    }
                }
            }
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Ringtone: clean old files", t)
            // Не ломаем установку из-за невозможности подчистить старую запись.
        }
    }

    private fun isOurGeneratedRingtone(context: Context, current: Uri, savedDelayed: Uri?): Boolean {
        if (savedDelayed != null && sameMediaUri(current, savedDelayed)) return true

        val clean = current.buildUpon().clearQuery().build()
        try {
            context.contentResolver.query(
                clean,
                arrayOf(MediaStore.Audio.Media.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0).orEmpty()
                    if (name.startsWith(FILE_PREFIX)) return true
                }
            }
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Ringtone: inspect MediaStore file", t)
        }
        return false
    }

    private fun sameMediaUri(a: Uri, b: Uri): Boolean =
        a.buildUpon().clearQuery().build().toString() == b.buildUpon().clearQuery().build().toString()

    private fun removeQueryParameter(uri: Uri, parameterToRemove: String): Uri {
        val names = try {
            uri.queryParameterNames
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Ringtone: read URI parameter names", t)
            emptySet()
        }
        if (!names.contains(parameterToRemove)) return uri

        val builder = uri.buildUpon().clearQuery()
        for (name in names) {
            if (name == parameterToRemove) continue
            val values = try {
                uri.getQueryParameters(name)
            } catch (t: Throwable) {
                DiagnosticsStore.saveError("Ringtone: read URI parameter $name", t)
                emptyList()
            }
            if (values.isEmpty()) {
                builder.appendQueryParameter(name, null)
            } else {
                for (value in values) builder.appendQueryParameter(name, value)
            }
        }
        return builder.build()
    }

    private fun pcmOffsetFromMillis(
        pcm16: ByteArray,
        sampleRate: Int,
        channels: Int,
        offsetMs: Long
    ): Int {
        if (offsetMs <= 0L) return 0
        val frameSize = channels * 2
        if (frameSize <= 0 || sampleRate <= 0) return 0

        val frames = sampleRate.toLong() * offsetMs / 1000L
        val byteOffsetLong = frames * frameSize
        if (byteOffsetLong <= 0L) return 0
        if (byteOffsetLong >= pcm16.size) {
            error("Samsung highlight_offset exceeds ringtone duration")
        }

        return (byteOffsetLong / frameSize * frameSize).toInt()
    }

    private data class DecodedPcm(
        val pcm16: ByteArray,
        val sampleRate: Int,
        val channels: Int
    )

    private fun decodeToPcm16(context: Context, source: Uri): DecodedPcm {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, source, null)

            var audioTrack = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    inputFormat = format
                    break
                }
            }

            if (audioTrack < 0 || inputFormat == null) error("No audio track found in ringtone")

            extractor.selectTrack(audioTrack)
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Unknown ringtone format")

            val decoder = MediaCodec.createDecoderByType(mime)
            try {
                decoder.configure(inputFormat, null, null, 0)
                decoder.start()

                val output = ByteArrayOutputStream()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var sampleRate = inputFormat.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
                var channels = inputFormat.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
                var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = decoder.dequeueInputBuffer(10000)
                        if (inputIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputIndex)
                                ?: error("Decoder input buffer unavailable")
                            inputBuffer.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = decoder.dequeueOutputBuffer(info, 10000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = decoder.outputFormat
                            sampleRate = format.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                            channels = format.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                            pcmEncoding = format.getIntegerOr(
                                MediaFormat.KEY_PCM_ENCODING,
                                AudioFormat.ENCODING_PCM_16BIT
                            )
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outputIndex >= 0) {
                            val buffer = decoder.getOutputBuffer(outputIndex)
                            if (buffer != null && info.size > 0) {
                                val data = ByteArray(info.size)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                buffer.get(data)
                                output.write(convertToPcm16(data, pcmEncoding))
                            }
                            outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }

                val pcm = output.toByteArray()
                if (pcm.isEmpty()) error("Failed to decode ringtone")
                return DecodedPcm(pcm, sampleRate, channels)
            } finally {
                try {
                    decoder.stop()
                } catch (t: Throwable) {
                    DiagnosticsStore.saveError("Ringtone: stop decoder", t)
                }
                decoder.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun convertToPcm16(data: ByteArray, encoding: Int): ByteArray {
        return when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> data
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                val out = ByteBuffer.allocate((data.size / 4) * 2).order(ByteOrder.LITTLE_ENDIAN)
                while (input.remaining() >= 4) {
                    val f = input.float.coerceIn(-1f, 1f)
                    val s = (f * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    out.putShort(s)
                }
                out.array()
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val out = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (b in data) {
                    val unsigned = b.toInt() and 0xff
                    out.putShort(((unsigned - 128) shl 8).toShort())
                }
                out.array()
            }
            else -> error("Unsupported decoder PCM format: $encoding")
        }
    }

    private fun writeDelayedWav(
        output: OutputStream,
        pcm16: ByteArray,
        pcmOffset: Int,
        sampleRate: Int,
        channels: Int,
        delayMs: Int
    ) {
        val bytesPerSample = 2
        val silenceBytesLong = sampleRate.toLong() * channels * bytesPerSample * delayMs / 1000L
        val pcmSize = pcm16.size - pcmOffset
        val dataSizeLong = silenceBytesLong + pcmSize
        if (silenceBytesLong > Int.MAX_VALUE || dataSizeLong > Int.MAX_VALUE) {
            error("Ringtone is too large")
        }
        val silenceBytes = silenceBytesLong.toInt()
        val dataSize = dataSizeLong.toInt()

        val out = DataOutputStream(output)

        fun writeAscii(s: String) = out.writeBytes(s)
        fun writeLe16(v: Int) {
            out.writeByte(v and 0xff)
            out.writeByte((v ushr 8) and 0xff)
        }
        fun writeLe32(v: Int) {
            out.writeByte(v and 0xff)
            out.writeByte((v ushr 8) and 0xff)
            out.writeByte((v ushr 16) and 0xff)
            out.writeByte((v ushr 24) and 0xff)
        }

        writeAscii("RIFF")
        writeLe32(36 + dataSize)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeLe32(16)
        writeLe16(1)
        writeLe16(channels)
        writeLe32(sampleRate)
        writeLe32(sampleRate * channels * bytesPerSample)
        writeLe16(channels * bytesPerSample)
        writeLe16(16)
        writeAscii("data")
        writeLe32(dataSize)

        // Оригинальный PCM не меняем. Последние 15 мс добавленной тишины
        // плавно подводим к первому sample выбранного (с учётом highlight) фрагмента.
        val frameSize = channels * bytesPerSample
        val rampFrames = if (pcmSize >= frameSize && silenceBytes >= frameSize && sampleRate > 0) {
            minOf(sampleRate * 15 / 1000, silenceBytes / frameSize).takeIf { it > 1 } ?: 0
        } else {
            0
        }

        writeZeros(out, silenceBytes - rampFrames * frameSize)

        if (rampFrames > 0) {
            val firstSamples = IntArray(channels)
            for (channel in 0 until channels) {
                val src = pcmOffset + channel * 2
                firstSamples[channel] =
                    (((pcm16[src + 1].toInt() shl 8) or
                        (pcm16[src].toInt() and 0xff)).toShort().toInt())
            }

            for (frame in 0 until rampFrames) {
                val gain = frame.toFloat() / rampFrames.toFloat()
                for (channel in 0 until channels) {
                    val sample = (firstSamples[channel] * gain).toInt().coerceIn(-32768, 32767)
                    writeLe16(sample)
                }
            }
        }

        out.write(pcm16, pcmOffset, pcmSize)
        out.flush()
    }

    private fun writeZeros(output: OutputStream, byteCount: Int) {
        val buffer = ByteArray(minOf(byteCount, 8192))
        var remaining = byteCount
        while (remaining > 0) {
            val count = minOf(remaining, buffer.size)
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun MediaFormat.getIntegerOr(key: String, fallback: Int): Int =
        try {
            if (containsKey(key)) getInteger(key) else fallback
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Ringtone: read MediaFormat.$key", t)
            fallback
        }
}
