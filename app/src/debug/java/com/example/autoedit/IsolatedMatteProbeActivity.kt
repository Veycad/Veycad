package com.example.autoedit

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.widget.TextView
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.io.File
import java.nio.ByteOrder
import kotlin.math.ceil

/** Debug-only proof that the six-class matte is useful before it is integrated into production. */
class IsolatedMatteProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            setBackgroundColor(Color.rgb(9, 9, 11))
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            text = "Изолированная проверка alpha-маски…"
        }
        setContentView(status)
        Thread({ probe(status) }, "veycad-matte-probe").start()
    }

    private fun probe(status: TextView) {
        val source = File(requireNotNull(intent.getStringExtra("source")))
        val outputDirectory = File(requireNotNull(intent.getStringExtra("output"))).apply { mkdirs() }
        val marker = File(outputDirectory, "complete.result").apply { delete() }
        runCatching {
            require(source.isFile) { "Probe source does not exist: ${source.absolutePath}" }
            val options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET_PATH).build())
                .setRunningMode(RunningMode.VIDEO)
                .setOutputConfidenceMasks(true)
                .setOutputCategoryMask(false)
                .build()
            ImageSegmenter.createFromOptions(this, options).use { segmenter ->
                val retriever = MediaMetadataRetriever().apply { setDataSource(source.absolutePath) }
                try {
                    val durationUs = requireNotNull(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ) * 1_000L
                    val probeStartUs = intent.getLongExtra("start_us", 0L).coerceIn(0L, durationUs - 1L)
                    val probeDurationUs = minOf(durationUs - probeStartUs,
                        intent.getLongExtra("duration_us", PROBE_DURATION_US).coerceIn(1L, PROBE_DURATION_US))
                    val probeIntervalUs = intent.getLongExtra("interval_us", PROBE_INTERVAL_US)
                        .coerceIn(33_333L, PROBE_INTERVAL_US)
                    val count = ceil(probeDurationUs.toDouble() / probeIntervalUs).toInt()
                        .coerceAtLeast(1)
                    val rows = ceil(count.toDouble() / COLUMNS).toInt()
                    val sheet = Bitmap.createBitmap(
                        COLUMNS * TILE_WIDTH,
                        rows * (TILE_HEIGHT + LABEL_HEIGHT),
                        Bitmap.Config.ARGB_8888
                    )
                    val canvas = Canvas(sheet).apply { drawColor(Color.BLACK) }
                    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        textSize = 20f
                    }
                    var maskWidth = 0
                    var maskHeight = 0
                    repeat(count) { index ->
                        val timeUs = probeStartUs + minOf(index * probeIntervalUs, probeDurationUs - 1L)
                        val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            retriever.getScaledFrameAtTime(
                                timeUs, MediaMetadataRetriever.OPTION_CLOSEST,
                                PROBE_WIDTH, PROBE_HEIGHT
                            )
                        } else {
                            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                                ?.let { original ->
                                    Bitmap.createScaledBitmap(original, PROBE_WIDTH, PROBE_HEIGHT, true)
                                        .also { if (it !== original) original.recycle() }
                                }
                        } ?: return@repeat
                        // Source-aligned, uncomposited frames for independent local matte
                        // comparisons. Debug-only and opt-in; never packaged with the APK.
                        if (intent.getBooleanExtra("export_frames", false)) {
                            File(outputDirectory, "source-${timeUs.toString().padStart(12, '0')}.png")
                                .outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            if (intent.getBooleanExtra("export_native", false)) {
                                val native = requireNotNull(retriever.getFrameAtTime(
                                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST))
                                try {
                                    File(outputDirectory, "native-${timeUs.toString().padStart(12, '0')}.png")
                                        .outputStream().use { native.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                } finally { native.recycle() }
                            }
                        }
                        val mpImage = BitmapImageBuilder(frame).build()
                        try {
                            val result = segmenter.segmentForVideo(mpImage, timeUs / 1_000L)
                            val masks = result.confidenceMasks().orElseThrow {
                                IllegalStateException("SelfieMulticlass returned no confidence masks")
                            }
                            require(masks.size >= PERSON_CLASS_COUNT) {
                                "Expected six confidence masks, received ${masks.size}"
                            }
                            maskWidth = masks.first().width
                            maskHeight = masks.first().height
                            val channels = masks.take(PERSON_CLASS_COUNT).map { mask ->
                                val buffer = ByteBufferExtractor.extract(mask)
                                    .order(ByteOrder.nativeOrder()).apply { rewind() }
                                FloatArray(mask.width * mask.height) { buffer.float.coerceIn(0f, 1f) }
                            }
                            val person = FloatArray(maskWidth * maskHeight) { pixel ->
                                (channels[HAIR_CLASS][pixel] +
                                    channels[BODY_SKIN_CLASS][pixel] +
                                    channels[FACE_SKIN_CLASS][pixel] +
                                    channels[CLOTHES_CLASS][pixel] +
                                    channels[ACCESSORIES_CLASS][pixel]).coerceIn(0f, 1f)
                            }
                            if (intent.getBooleanExtra("export_person_masks", false)) {
                                // Export the actual production union, not the unfiltered
                                // diagnostic sum above (which includes accessory hallucinations).
                                val union = PersonMaskUnion.combine(channels, maskWidth, maskHeight)
                                val pixels = IntArray(union.size) { i ->
                                    val v = (union[i] * 255f).toInt()
                                    Color.rgb(v, v, v)
                                }
                                val image = Bitmap.createBitmap(pixels, maskWidth, maskHeight,
                                    Bitmap.Config.ARGB_8888)
                                try {
                                    File(outputDirectory, "person-${timeUs.toString().padStart(12, '0')}.png")
                                        .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                } finally { image.recycle() }
                            }
                            if (index == 0 && intent.getBooleanExtra("export_channels", false)) {
                                channels.forEachIndexed { channel, values ->
                                    val pixels = IntArray(values.size) { i ->
                                        val v = (values[i] * 255f).toInt()
                                        Color.rgb(v, v, v)
                                    }
                                    val image = Bitmap.createBitmap(pixels, maskWidth, maskHeight,
                                        Bitmap.Config.ARGB_8888)
                                    File(outputDirectory, "channel-$channel.png").outputStream().use {
                                        image.compress(Bitmap.CompressFormat.PNG, 100, it)
                                    }
                                    image.recycle()
                                }
                            }
                            val cutout = compositeOnBlack(frame, person, maskWidth, maskHeight)
                            val left = index % COLUMNS * TILE_WIDTH
                            val top = index / COLUMNS * (TILE_HEIGHT + LABEL_HEIGHT)
                            canvas.drawBitmap(
                                cutout,
                                null,
                                Rect(left, top, left + TILE_WIDTH, top + TILE_HEIGHT),
                                null
                            )
                            canvas.drawText(
                                "%.2fs".format(timeUs / 1_000_000.0),
                                left + 5f,
                                top + TILE_HEIGHT + 22f,
                                label
                            )
                            cutout.recycle()
                            masks.forEach { it.close() }
                        } finally {
                            mpImage.close()
                            frame.recycle()
                        }
                    }
                    File(outputDirectory, "multiclass-cutout.jpg").outputStream().use {
                        sheet.compress(Bitmap.CompressFormat.JPEG, 94, it)
                    }
                    sheet.recycle()
                    marker.writeText(
                        "status=ok\nlabels=${segmenter.labels.joinToString(",")}\n" +
                            "frames=$count\nmask_width=$maskWidth\nmask_height=$maskHeight\n"
                    )
                } finally {
                    retriever.release()
                }
            }
            runOnUiThread { status.text = "Изолированная alpha-проверка завершена" }
        }.onFailure { error ->
            marker.writeText(
                "status=failed\ntype=${error.javaClass.name}\ndetail=${error.message.orEmpty()}\n"
            )
            runOnUiThread { status.text = "Ошибка alpha-проверки\n${error.message.orEmpty()}" }
        }
    }

    private fun compositeOnBlack(
        source: Bitmap,
        alpha: FloatArray,
        maskWidth: Int,
        maskHeight: Int
    ): Bitmap {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val output = IntArray(pixels.size) { index ->
            val x = index % source.width
            val y = index / source.width
            val maskX = (x * maskWidth / source.width).coerceIn(0, maskWidth - 1)
            val maskY = (y * maskHeight / source.height).coerceIn(0, maskHeight - 1)
            val matte = alpha[maskY * maskWidth + maskX]
            val colour = pixels[index]
            Color.rgb(
                ((colour shr 16 and 0xff) * matte).toInt().coerceIn(0, 255),
                ((colour shr 8 and 0xff) * matte).toInt().coerceIn(0, 255),
                ((colour and 0xff) * matte).toInt().coerceIn(0, 255)
            )
        }
        return Bitmap.createBitmap(output, source.width, source.height, Bitmap.Config.ARGB_8888)
    }

    companion object {
        private const val MODEL_ASSET_PATH = "models/selfie_multiclass_256x256.tflite"
        private const val PROBE_DURATION_US = 3_000_000L
        private const val PROBE_INTERVAL_US = 100_000L
        private const val PROBE_WIDTH = 270
        private const val PROBE_HEIGHT = 480
        private const val TILE_WIDTH = 180
        private const val TILE_HEIGHT = 320
        private const val LABEL_HEIGHT = 30
        private const val COLUMNS = 5
        private const val PERSON_CLASS_COUNT = 6
        private const val HAIR_CLASS = 1
        private const val BODY_SKIN_CLASS = 2
        private const val FACE_SKIN_CLASS = 3
        private const val CLOTHES_CLASS = 4
        private const val ACCESSORIES_CLASS = 5
    }
}
