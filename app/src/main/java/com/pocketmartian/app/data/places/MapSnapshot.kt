package com.pocketmartian.app.data.places

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * The picture of a [PlacesResult] shown in the chat: OpenStreetMap tiles with the user,
 * numbered pins and a line to each.
 *
 * A picture rather than a live map inside the answer: the chat list recycles its rows
 * while scrolling, and a map view in each would reload its tiles every time it came back
 * on screen, and fight the list for drags. Tapping the picture opens the live map.
 * It is drawn once, when the answer arrives, and kept as a file like Grok's pictures, so
 * a saved conversation shows it again without asking the tile server.
 */
internal object MapSnapshot {

    private const val TAG = "MapSnapshot"
    private const val DIR = "maps"
    private const val TILE = 256
    const val TILE_HOST = "tile.openstreetmap.org"

    /** Size in map pixels; drawn at [SCALE] so the map's own labels stay readable on a phone. */
    const val WIDTH = 480
    const val HEIGHT = 264
    private const val SCALE = 2f
    private const val PADDING = 36

    fun directory(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** Draw and save the map, or null when no tile could be fetched (offline). */
    suspend fun render(context: Context, http: OkHttpClient, result: PlacesResult): File? = withContext(Dispatchers.IO) {
        val points = listOf(result.origin.lat to result.origin.lon) + result.places.map { it.lat to it.lon }
        val zoom = fitZoom(points, WIDTH, HEIGHT, PADDING)
        val xs = points.map { lonToX(it.second, zoom) }
        val ys = points.map { latToY(it.first, zoom) }
        val left = (xs.min() + xs.max()) / 2 - WIDTH / 2.0
        // Lowered by half a pin, since pins stand above their points.
        val top = (ys.min() + ys.max()) / 2 - HEIGHT / 2.0 - PIN_HEIGHT / 2.0

        val bitmap = Bitmap.createBitmap((WIDTH * SCALE).toInt(), (HEIGHT * SCALE).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(SCALE, SCALE)
        canvas.drawColor(Color.rgb(0xE8, 0xE6, 0xE0))

        val tiles = tilesCovering(left, top, WIDTH, HEIGHT, zoom)
        // One tile first, so the host is looked up once; then the rest together. A tile
        // that fails gets one more try.
        val first = tiles.first().let { (tx, ty) -> Triple(tx, ty, fetchTile(http, zoom, tx, ty)) }
        val fetched = listOf(first) + coroutineScope {
            tiles.drop(1).map { (tx, ty) -> async { Triple(tx, ty, fetchTile(http, zoom, tx, ty)) } }.awaitAll()
        }.map { (tx, ty, tile) -> Triple(tx, ty, tile ?: fetchTile(http, zoom, tx, ty)) }
        // A map mostly blank looks broken; without a picture the card offers the live map.
        val missing = fetched.count { it.third == null }
        if (missing * 4 > fetched.size) {
            Log.w(TAG, "$missing of ${fetched.size} tiles failed; no picture")
            fetched.forEach { it.third?.recycle() }
            return@withContext null
        }
        val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        for ((tx, ty, tile) in fetched) {
            tile ?: continue
            val dx = (tx * TILE - left).toFloat()
            val dy = (ty * TILE - top).toFloat()
            canvas.drawBitmap(tile, null, RectF(dx, dy, dx + TILE, dy + TILE), tilePaint)
            tile.recycle()
        }

        val ox = (xs[0] - left).toFloat()
        val oy = (ys[0] - top).toFloat()
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 0x19, 0x76, 0xD2)
            strokeWidth = 2f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f)
        }
        result.places.forEachIndexed { i, _ ->
            canvas.drawLine(ox, oy, (xs[i + 1] - left).toFloat(), (ys[i + 1] - top).toFloat(), line)
        }
        drawOrigin(canvas, ox, oy, result.origin.isUser)
        // Drawn last to first so pin 1 sits on top where pins overlap.
        result.places.indices.reversed().forEach { i ->
            drawPin(canvas, (xs[i + 1] - left).toFloat(), (ys[i + 1] - top).toFloat(), result.places[i].number)
        }
        drawAttribution(canvas)

        val file = File(directory(context), "${UUID.randomUUID()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        file
    }

    /** A numbered pin for the live map, its point at the bottom centre, at [scale] pixels per map pixel. */
    fun pinBitmap(number: Int, scale: Float): Bitmap {
        val bitmap = Bitmap.createBitmap((22 * scale).toInt(), (36 * scale).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        drawPin(canvas, 11f, 35f, number)
        return bitmap
    }

    /** The dot for where the search was made from, for the live map, centred. */
    fun originBitmap(isUser: Boolean, scale: Float): Bitmap {
        val bitmap = Bitmap.createBitmap((30 * scale).toInt(), (30 * scale).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        drawOrigin(canvas, 15f, 15f, isUser)
        return bitmap
    }

    /** Delete maps no saved conversation refers to any more; [keep] holds the paths in use. */
    fun purgeUnreferenced(context: Context, keep: Set<String>) {
        val kept = keep.mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }.toSet()
        directory(context).listFiles()?.forEach { file ->
            if (file.canonicalPath !in kept) file.delete()
        }
    }

    private fun fetchTile(http: OkHttpClient, zoom: Int, x: Int, y: Int): Bitmap? {
        val n = 1 shl zoom
        if (y !in 0 until n) return null
        val wrappedX = ((x % n) + n) % n
        return try {
            val request = Request.Builder()
                .url("https://$TILE_HOST/$zoom/$wrappedX/$y.png")
                .header("User-Agent", PlacesRepository.USER_AGENT)
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tile $zoom/$x/$y failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun drawOrigin(canvas: Canvas, x: Float, y: Float, isUser: Boolean) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (isUser) {
            paint.color = Color.argb(60, 0x19, 0x76, 0xD2)
            canvas.drawCircle(x, y, 14f, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, 7.5f, paint)
            paint.color = Color.rgb(0x19, 0x76, 0xD2)
            canvas.drawCircle(x, y, 5.5f, paint)
        } else {
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, 7f, paint)
            paint.color = Color.rgb(0x42, 0x42, 0x42)
            canvas.drawCircle(x, y, 5f, paint)
        }
    }

    /** A teardrop pin whose point is at ([x], [y]), with its number in the head. */
    private fun drawPin(canvas: Canvas, x: Float, y: Float, number: Int) {
        val r = 10f
        val cy = y - 2.4f * r
        val path = Path().apply {
            moveTo(x, y)
            cubicTo(x - r * 0.4f, y - r * 0.9f, x - r, cy + r * 0.6f, x - r, cy)
            arcTo(RectF(x - r, cy - r, x + r, cy + r), 180f, 180f)
            cubicTo(x + r, cy + r * 0.6f, x + r * 0.4f, y - r * 0.9f, x, y)
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xD3, 0x2F, 0x2F) })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
        })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(number.toString(), x, cy + 4f, text)
    }

    /** OpenStreetMap's licence asks for this credit on every map made from its data. */
    private fun drawAttribution(canvas: Canvas) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x33, 0x33, 0x33)
            textSize = 8f
            textAlign = Paint.Align.RIGHT
        }
        val label = ATTRIBUTION
        val w = text.measureText(label)
        canvas.drawRect(
            WIDTH - w - 6f, HEIGHT - 12f, WIDTH.toFloat(), HEIGHT.toFloat(),
            Paint().apply { color = Color.argb(190, 255, 255, 255) }
        )
        canvas.drawText(label, WIDTH - 3f, HEIGHT - 3.5f, text)
    }
}

internal const val ATTRIBUTION = "© OpenStreetMap contributors"

// ---- Web Mercator, in pixels of a 256-pixel-tile world at zoom z. ----

internal fun lonToX(lon: Double, zoom: Int): Double = (lon + 180.0) / 360.0 * worldSize(zoom)

internal fun latToY(lat: Double, zoom: Int): Double {
    val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
    return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * worldSize(zoom)
}

private fun worldSize(zoom: Int): Double = 256.0 * (1 shl zoom)

/**
 * The closest zoom at which all [points] (lat to lon) fit in a [width] by [height] view
 * with [padding] round the edge. Never closer than street level, so one place alone, or
 * a place next door, still shows the streets around it.
 */
internal fun fitZoom(points: List<Pair<Double, Double>>, width: Int, height: Int, padding: Int, maxZoom: Int = 17, minZoom: Int = 2): Int {
    for (z in maxZoom downTo minZoom) {
        val xs = points.map { lonToX(it.second, z) }
        val ys = points.map { latToY(it.first, z) }
        // Pins stand above their point, so the top needs room for a whole pin.
        if (xs.max() - xs.min() <= width - 2 * padding && ys.max() - ys.min() <= height - 2 * padding - PIN_HEIGHT) return z
    }
    return minZoom
}

/** Tile numbers (x to y) covering the view whose top-left is at ([left], [top]). */
internal fun tilesCovering(left: Double, top: Double, width: Int, height: Int, zoom: Int): List<Pair<Int, Int>> {
    val x0 = floor(left / 256).toInt()
    val x1 = floor((left + width - 1) / 256).toInt()
    val y0 = floor(top / 256).toInt().coerceAtLeast(0)
    val y1 = floor((top + height - 1) / 256).toInt().coerceAtMost((1 shl zoom) - 1)
    return (y0..y1).flatMap { y -> (x0..x1).map { x -> x to y } }
}

private const val PIN_HEIGHT = 34
