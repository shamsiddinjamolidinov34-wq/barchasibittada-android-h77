package uz.barchasibittada

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** PCM (16 bit, mono) ni AAC (M4A) ga o'raydi. Katta kitoblar uchun ham xotirani ko'p olmaydi. */
class AacWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int = 1,
    bitRate: Int = 64000
) {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var track = -1
    private var muxerStarted = false
    private var totalSamples = 0L
    private val info = MediaCodec.BufferInfo()

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    val seconds: Double
        get() = totalSamples.toDouble() / sampleRate.toDouble()

    fun write(pcm: ByteArray) {
        var offset = 0
        var guard = 0
        while (offset < pcm.size) {
            val inIndex = codec.dequeueInputBuffer(10000)
            if (inIndex >= 0) {
                val buf = codec.getInputBuffer(inIndex)
                if (buf != null) {
                    buf.clear()
                    val n = minOf(buf.remaining(), pcm.size - offset)
                    buf.put(pcm, offset, n)
                    val ptsUs = totalSamples * 1000000L / sampleRate
                    codec.queueInputBuffer(inIndex, 0, n, ptsUs, 0)
                    offset += n
                    totalSamples += (n / (2 * channels)).toLong()
                }
                guard = 0
            } else {
                guard += 1
                if (guard > 3000) throw IllegalStateException("AAC kodlagich javob bermadi")
            }
            drain(false)
        }
    }

    private fun drain(untilEnd: Boolean) {
        var spins = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (untilEnd) 10000L else 0L)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEnd) return
                spins += 1
                if (spins > 3000) return
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.outputFormat)
                muxer.start()
                muxerStarted = true
            } else if (idx >= 0) {
                val out = codec.getOutputBuffer(idx)
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                if (out != null && info.size > 0 && muxerStarted) {
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    muxer.writeSampleData(track, out, info)
                }
                codec.releaseOutputBuffer(idx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
            }
        }
    }

    fun finish() {
        try {
            val inIndex = codec.dequeueInputBuffer(1000000)
            if (inIndex >= 0) {
                val ptsUs = totalSamples * 1000000L / sampleRate
                codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(true)
        } catch (e: Exception) {
            // yakunlashda xato bo'lsa ham fayl yopiladi
        }
        try {
            codec.stop()
        } catch (e: Exception) {
            // e'tiborsiz
        }
        try {
            codec.release()
        } catch (e: Exception) {
            // e'tiborsiz
        }
        try {
            if (muxerStarted) muxer.stop()
        } catch (e: Exception) {
            // e'tiborsiz
        }
        try {
            muxer.release()
        } catch (e: Exception) {
            // e'tiborsiz
        }
    }
}

interface AudioSink {
    val file: File
    fun add(chunk: AudioData)
    fun seconds(): Double
    fun close()
}

/** MP3 bo'laklarini ketma-ket bitta faylga yozadi (MP3 kadrlarini shunchaki birlashtirish mumkin). */
class Mp3Sink(override val file: File, private val bitrate: Int) : AudioSink {
    private val out = FileOutputStream(file)
    private var written = 0L

    override fun add(chunk: AudioData) {
        out.write(chunk.bytes)
        written += chunk.bytes.size.toLong()
    }

    override fun seconds(): Double = written.toDouble() * 8.0 / bitrate.toDouble()

    override fun close() {
        try {
            out.flush()
            out.close()
        } catch (e: Exception) {
            // e'tiborsiz
        }
    }
}

/** PCM ni M4A (AAC) ga yozadi; kodlagich ishlamasa WAV ga o'tadi. */
class PcmSink(dir: File, baseName: String, private val rate: Int) : AudioSink {
    private var aac: AacWriter? = null
    private var wav: RandomAccessFile? = null
    private var pcmBytes = 0L
    override val file: File

    init {
        var f = File(dir, baseName + ".m4a")
        try {
            aac = AacWriter(f, rate)
        } catch (e: Throwable) {
            aac = null
            try {
                f.delete()
            } catch (x: Exception) {
                // e'tiborsiz
            }
            f = File(dir, baseName + ".wav")
            val raf = RandomAccessFile(f, "rw")
            raf.setLength(0)
            raf.write(ByteArray(44))
            wav = raf
        }
        file = f
    }

    override fun add(chunk: AudioData) {
        val a = aac
        if (a != null) {
            a.write(chunk.bytes)
        } else {
            wav?.write(chunk.bytes)
        }
        pcmBytes += chunk.bytes.size.toLong()
    }

    override fun seconds(): Double {
        val a = aac
        if (a != null) return a.seconds
        return pcmBytes.toDouble() / (2.0 * rate.toDouble())
    }

    override fun close() {
        val a = aac
        if (a != null) {
            a.finish()
            return
        }
        val raf = wav ?: return
        try {
            val dataLen = pcmBytes.toInt()
            val header = ByteArray(44)
            fun put32(pos: Int, v: Int) {
                header[pos] = (v and 0xFF).toByte()
                header[pos + 1] = ((v shr 8) and 0xFF).toByte()
                header[pos + 2] = ((v shr 16) and 0xFF).toByte()
                header[pos + 3] = ((v shr 24) and 0xFF).toByte()
            }
            fun put16(pos: Int, v: Int) {
                header[pos] = (v and 0xFF).toByte()
                header[pos + 1] = ((v shr 8) and 0xFF).toByte()
            }
            "RIFF".toByteArray().copyInto(header, 0)
            put32(4, 36 + dataLen)
            "WAVE".toByteArray().copyInto(header, 8)
            "fmt ".toByteArray().copyInto(header, 12)
            put32(16, 16)
            put16(20, 1)
            put16(22, 1)
            put32(24, rate)
            put32(28, rate * 2)
            put16(32, 2)
            put16(34, 16)
            "data".toByteArray().copyInto(header, 36)
            put32(40, dataLen)
            raf.seek(0)
            raf.write(header)
            raf.close()
        } catch (e: Exception) {
            // e'tiborsiz
        }
    }
}

/**
 * Audio qismlarini boshqaradi: limitSeconds = 0 bo'lsa — bitta fayl, aks holda har biri
 * shuncha soniyali bo'laklar. Telegram chegarasi (50 MB) ilovada ishlatilmaydi.
 */
class PartManager(
    private val dir: File,
    private val baseName: String,
    private val pcm: Boolean,
    private val rate: Int,
    private val mp3Bitrate: Int,
    private val limitSeconds: Double
) {
    val files = ArrayList<File>()
    private var sink: AudioSink? = null
    private var index = 0
    var estimatedSeconds = 0.0
        private set

    fun add(chunk: AudioData) {
        var s = sink
        if (s != null && limitSeconds > 0.0 && s.seconds() >= limitSeconds) {
            estimatedSeconds += s.seconds()
            s.close()
            sink = null
            s = null
        }
        if (s == null) {
            index += 1
            val name = if (limitSeconds > 0.0) baseName + "-" + index else baseName
            s = if (pcm) PcmSink(dir, name, rate) else Mp3Sink(File(dir, name + ".mp3"), mp3Bitrate)
            sink = s
            files.add(s.file)
        }
        s.add(chunk)
    }

    fun finish(): List<File> {
        val s = sink
        if (s != null) {
            estimatedSeconds += s.seconds()
            s.close()
        }
        sink = null
        return files
    }
}

object AudioInfo {
    fun durationSec(path: String): Int {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            return (ms / 1000L).toInt()
        } catch (e: Exception) {
            return 0
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
                // e'tiborsiz
            }
        }
    }

    fun clock(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d soat %d daqiqa".format(h, m) else if (m > 0) "%d daqiqa %d soniya".format(m, s) else "%d soniya".format(s)
    }
}

object Player {
    private var mp: MediaPlayer? = null
    var current by mutableStateOf("")
        private set

    fun toggle(path: String) {
        if (current == path) stop() else play(path)
    }

    fun play(path: String) {
        stop()
        try {
            val m = MediaPlayer()
            m.setDataSource(path)
            m.setOnCompletionListener { stop() }
            m.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            m.prepare()
            m.start()
            mp = m
            current = path
        } catch (e: Exception) {
            stop()
            Ui.toast("Ovozni ijro etib bo'lmadi.")
        }
    }

    fun stop() {
        val m = mp
        mp = null
        if (m != null) {
            try {
                m.stop()
            } catch (e: Exception) {
                // e'tiborsiz
            }
            try {
                m.release()
            } catch (e: Exception) {
                // e'tiborsiz
            }
        }
        current = ""
    }
}

object Recorder {
    private var rec: MediaRecorder? = null
    private var target: File? = null

    @Suppress("DEPRECATION")
    fun start(ctx: Context): Boolean {
        try {
            val f = File(ctx.cacheDir, "ovoz-" + System.currentTimeMillis() + ".m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            rec = r
            target = f
            return true
        } catch (e: Exception) {
            cleanup()
            return false
        }
    }

    /** Yozuvni to'xtatadi va faylni qaytaradi (juda qisqa bo'lsa null). */
    fun stop(): File? {
        val r = rec
        val f = target
        rec = null
        target = null
        if (r == null || f == null) return null
        var ok = true
        try {
            r.stop()
        } catch (e: RuntimeException) {
            ok = false
        }
        try {
            r.release()
        } catch (e: Exception) {
            // e'tiborsiz
        }
        if (!ok || !f.exists() || f.length() < 800L) {
            try {
                f.delete()
            } catch (e: Exception) {
                // e'tiborsiz
            }
            return null
        }
        return f
    }

    private fun cleanup() {
        try {
            rec?.release()
        } catch (e: Exception) {
            // e'tiborsiz
        }
        rec = null
        try {
            target?.delete()
        } catch (e: Exception) {
            // e'tiborsiz
        }
        target = null
    }
}

object ImageKit {
    /** Rasmni JPEG ga keltiradi (burilishni to'g'rilaydi, kattaligini cheklaydi). */
    fun prepare(file: File, maxSide: Int = 2048): ByteArray? {
        try {
            val path = file.absolutePath
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > maxSide * 2) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            var bmp = BitmapFactory.decodeFile(path, opts) ?: return null

            val deg = rotationOf(path)
            if (deg != 0) {
                val m = Matrix()
                m.postRotate(deg.toFloat())
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rotated !== bmp) bmp.recycle()
                bmp = rotated
            }
            val big = maxOf(bmp.width, bmp.height)
            if (big > maxSide) {
                val k = maxSide.toFloat() / big.toFloat()
                val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
            bmp.recycle()
            return out.toByteArray()
        } catch (e: Throwable) {
            return null
        }
    }

    @Suppress("DEPRECATION")
    private fun rotationOf(path: String): Int {
        try {
            val exif = android.media.ExifInterface(path)
            val o = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
            return when (o) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Throwable) {
            return 0
        }
    }
}

object PdfKit {
    fun pageCount(file: File): Int {
        var pfd: ParcelFileDescriptor? = null
        var r: PdfRenderer? = null
        try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd = fd
            val rr = PdfRenderer(fd)
            r = rr
            return rr.pageCount
        } catch (e: Throwable) {
            return 0
        } finally {
            try {
                r?.close()
            } catch (e: Exception) {
                // e'tiborsiz
            }
            try {
                pfd?.close()
            } catch (e: Exception) {
                // e'tiborsiz
            }
        }
    }

    /** PDF sahifasini JPEG ga aylantiradi (skanerlangan PDF ni o'qish uchun). */
    fun renderPage(file: File, index: Int, width: Int = 1600): ByteArray? {
        var pfd: ParcelFileDescriptor? = null
        var r: PdfRenderer? = null
        try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd = fd
            val rr = PdfRenderer(fd)
            r = rr
            if (index < 0 || index >= rr.pageCount) return null
            val page = rr.openPage(index)
            try {
                val h = (page.height.toFloat() / page.width.toFloat() * width.toFloat()).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
                bmp.recycle()
                return out.toByteArray()
            } finally {
                page.close()
            }
        } catch (e: Throwable) {
            return null
        } finally {
            try {
                r?.close()
            } catch (e: Exception) {
                // e'tiborsiz
            }
            try {
                pfd?.close()
            } catch (e: Exception) {
                // e'tiborsiz
            }
        }
    }
}
