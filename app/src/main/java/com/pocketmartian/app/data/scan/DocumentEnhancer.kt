package com.pocketmartian.app.data.scan

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Rows of ARGB pixels that can be read and written a block at a time. Lets the enhancer
 * work through a large bitmap in small pieces, and lets tests run it on a plain array.
 */
internal interface PixelGrid {
    val width: Int
    val height: Int
    fun readRows(y: Int, rows: Int, into: IntArray)
    fun writeRows(y: Int, rows: Int, from: IntArray)
}

internal class ArrayPixelGrid(override val width: Int, override val height: Int, val pixels: IntArray) : PixelGrid {
    override fun readRows(y: Int, rows: Int, into: IntArray) {
        System.arraycopy(pixels, y * width, into, 0, rows * width)
    }

    override fun writeRows(y: Int, rows: Int, from: IntArray) {
        System.arraycopy(from, 0, pixels, y * width, rows * width)
    }
}

/**
 * Share of a flattened page (0..1) that is bare paper: bright, and of the one colour that
 * most of the bright part shares, whatever that colour is (white, cream, a pastel sheet, or
 * any of them under warm light).
 *
 * This is what separates print from pictures. Everything [DocumentEnhancer] does assumes a
 * sheet that is mostly one paper colour with ink on it: it divides that colour out, pushes
 * what is left toward black and white, and smooths colour as noise. On a page that is a
 * photograph all of that is damage. Reported from a real scan: a full-colour magazine
 * cover came out posterised and smeared. Measured on real pages, letters and forms score
 * 0.93 to 1.00 and that cover 0.14, so the line between them is not delicate.
 *
 * Even, bright and one colour is not enough on its own, though: the main colour must also
 * be one paper can have (see [isPaperColour]). Reported from a real scan: a lime-green
 * bank card scored as paper, so its green was divided out to white, leaving green halos
 * round the chip and logo and a pink chip.
 */
internal fun paperShare(page: PixelGrid): Float {
    val stride = max(1, max(page.width, page.height) / PAPER_SAMPLES_PER_SIDE)
    val row = IntArray(page.width)
    val samples = ArrayList<Int>()
    var y = stride / 2
    while (y < page.height) {
        page.readRows(y, 1, row)
        var x = stride / 2
        while (x < page.width) {
            samples += row[x]
            x += stride
        }
        y += stride
    }
    return paperShareOf(samples.toIntArray())
}

/** [paperShare] of a handful of ARGB samples taken from a page, however they were gathered. */
internal fun paperShareOf(samples: IntArray): Float {
    val n = samples.size
    if (n == 0) return 1f
    fun red(i: Int) = (samples[i] shr 16) and 0xFF
    fun green(i: Int) = (samples[i] shr 8) and 0xFF
    fun blue(i: Int) = samples[i] and 0xFF

    val luma = FloatArray(n) { (0.299f * red(it) + 0.587f * green(it) + 0.114f * blue(it)) / 255f }
    val white = luma.copyOf().also { it.sort() }[((n - 1) * 0.9f).toInt()]
    // Colour, apart from brightness, as two differences; counted over the bright samples.
    val bins = 2 * PAPER_COLOUR_BINS + 1
    val counts = IntArray(bins * bins)
    fun bin(difference: Int) =
        (Math.floorDiv(difference, PAPER_COLOUR_BIN_WIDTH) + PAPER_COLOUR_BINS).coerceIn(0, bins - 1)
    for (i in 0 until n) {
        if (luma[i] < PAPER_MIN_BRIGHTNESS * white) continue
        counts[bin(red(i) - green(i)) * bins + bin(blue(i) - green(i))]++
    }
    val mode = counts.indices.maxByOrNull { counts[it] } ?: return 1f
    val modeU = mode / bins
    val modeV = mode % bins
    var paper = 0
    var sumR = 0L
    var sumG = 0L
    var sumB = 0L
    for (i in 0 until n) {
        if (luma[i] < PAPER_MIN_BRIGHTNESS * white) continue
        if (abs(bin(red(i) - green(i)) - modeU) > 1 || abs(bin(blue(i) - green(i)) - modeV) > 1) continue
        paper++
        sumR += red(i)
        sumG += green(i)
        sumB += blue(i)
    }
    if (paper == 0) return 0f
    if (!isPaperColour(sumR.toFloat() / paper, sumG.toFloat() / paper, sumB.toFloat() / paper)) return 0f
    return paper.toFloat() / n
}

/**
 * Whether a sheet's main colour could be paper, as photographed: white, cream or a pastel
 * stock, under daylight, lamplight or anything between. Lighting moves colours along the
 * warm to cool line (reds and yellows against blues) and rarely by much after the camera's
 * white balance; paper stocks are pale. A strong colour, or a green or magenta tint, which
 * no ordinary light gives white paper, is a coloured object instead: a card, a cover, a
 * painted wall.
 */
internal fun isPaperColour(red: Float, green: Float, blue: Float): Boolean {
    val top = maxOf(red, green, blue)
    if (top <= 0f) return false
    val saturation = (top - minOf(red, green, blue)) / top
    val greenOrMagenta = abs(green - (red + blue) / 2f) / top
    return saturation <= MAX_PAPER_SATURATION && greenOrMagenta <= MAX_PAPER_GREEN_MAGENTA
}

/** Whether a flattened page is print on paper, which enhancement suits, or mostly pictures. */
internal fun looksLikePrint(page: PixelGrid): Boolean = paperShare(page) >= MIN_PAPER_SHARE

internal const val MIN_PAPER_SHARE = 0.5f

private const val PAPER_SAMPLES_PER_SIDE = 160
private const val PAPER_MIN_BRIGHTNESS = 0.6f
private const val PAPER_COLOUR_BIN_WIDTH = 10 // of 255: about 0.04
private const val PAPER_COLOUR_BINS = 16

/** Yellow stock measures about 0.46, white paper in uncorrected lamplight about 0.5. */
private const val MAX_PAPER_SATURATION = 0.6f

/** Pale green or pink stock measures about 0.17; the lime card that prompted this, 0.42. */
private const val MAX_PAPER_GREEN_MAGENTA = 0.3f

/**
 * Turns a photograph of a page into something that reads like a scan.
 *
 * A flattened photo is geometrically right and still looks soft, for reasons that have
 * nothing to do with pixel count: the paper is grey-beige rather than white, lit
 * unevenly, and the ink is mid-grey because the camera's own smoothing never lets a thin
 * stroke reach black. On a real scan the ink measured 0.55 against paper at 0.86.
 *
 *  1. **Flatten the light.** Estimate the bare paper's colour everywhere (shrink the
 *     image, grey-dilate so ink vanishes, smooth) and divide it out per channel. Shading
 *     and colour cast go together, and the paper becomes white.
 *  2. **Sharpen brightness only.** Detail lives in brightness. An unsharp mask there
 *     restores the edge contrast the camera smoothed away.
 *  3. **Set levels.** Map the ink towards black and clip the paper to white, which also
 *     hides whatever noise the sharpening raised in the paper.
 *  4. **Smooth colour instead.** Colour carries no detail worth keeping sharp, and the
 *     contrast gain would turn the camera's colour noise into red and blue speckle on
 *     every letter. Blurring colour averages that noise away while a pen stroke, whose
 *     colour is consistent along its length, keeps its blue. Colour fades out on white.
 */
internal object DocumentEnhancer {

    private const val MAP_MAX_SIDE = 200
    private const val DILATE_RADIUS = 3
    private const val UNSHARP_AMOUNT = 1.1f
    private const val UNSHARP_SIGMA = 1.2f
    private const val WHITE_POINT = 0.95f
    /**
     * Highest brightness that may be mapped to black. At 0.5, soft captures had their
     * already-blurred strokes thickened until small letters merged into blobs (seen on a
     * real scan's letterhead). Sharp captures are barely affected by the lower ceiling:
     * their ink is already darker than this.
     */
    private const val MAX_BLACK_POINT = 0.40f
    private const val CHROMA_GAIN = 1.6f
    private const val CHROMA_BOX_RADIUS = 2

    /** Flattened brightness range taken to be print and its edges, not paper and not solid black. */
    private const val INK_BAND_LOW = 0.25f
    private const val INK_BAND_HIGH = 0.85f

    /** Nothing darker than this share of the page's typical paper is treated as paper. */
    private const val PAPER_FLOOR = 0.6f

    /**
     * Nor is anything whose colour, brightness apart, differs from the typical paper's by
     * more than this, in shares of the total (r, g, b) / (r + g + b). Light falling unevenly
     * across a page shifts it by a few hundredths; a lime logo on white paper by 0.13,
     * highlighter yellow by 0.14.
     */
    private const val PAPER_TINT_TOLERANCE = 0.08f

    /** Rows of neighbouring context a block needs so its blurs are exact at the seams. */
    private const val PAD = 8
    private const val BLOCK_ROWS = 96

    /** Enhance [grid] in place. [bands] > 1 splits the work across threads via [runBands]. */
    fun enhance(
        grid: PixelGrid,
        bands: Int = 1,
        runBands: (List<() -> Unit>) -> Unit = { jobs -> jobs.forEach { it() } }
    ) {
        val w = grid.width
        val h = grid.height
        if (w < 8 || h < 8) return

        val paper = PaperMap.build(grid)
        val black = blackPoint(grid, paper)
        val kernel = gaussianKernel(UNSHARP_SIGMA)

        // Bands are processed independently and in place. Each needs a few rows of its
        // neighbours' *original* pixels for context, so those are copied out before any
        // band starts writing.
        val count = bands.coerceIn(1, max(1, h / (BLOCK_ROWS * 2)))
        val edges = IntArray(count + 1) { it * h / count }
        val jobs = (0 until count).map { b ->
            val from = edges[b]
            val to = edges[b + 1]
            val above = copyRows(grid, max(0, from - PAD), from)
            val below = copyRows(grid, to, min(h, to + PAD))
            val job: () -> Unit = { processBand(grid, from, to, above, below, paper, black, kernel) }
            job
        }
        runBands(jobs)
    }

    private fun copyRows(grid: PixelGrid, from: Int, to: Int): IntArray {
        val rows = to - from
        if (rows <= 0) return IntArray(0)
        return IntArray(rows * grid.width).also { readLocked(grid, from, rows, it) }
    }

    private fun readLocked(grid: PixelGrid, y: Int, rows: Int, into: IntArray) =
        synchronized(grid) { grid.readRows(y, rows, into) }

    private fun writeLocked(grid: PixelGrid, y: Int, rows: Int, from: IntArray) =
        synchronized(grid) { grid.writeRows(y, rows, from) }

    private fun processBand(
        grid: PixelGrid,
        bandFrom: Int,
        bandTo: Int,
        above: IntArray,
        below: IntArray,
        paper: PaperMap,
        black: Float,
        kernel: FloatArray
    ) {
        val w = grid.width
        // Original pixels of the rows just above the block being processed. Starts as the
        // neighbouring band's rows, then rolls forward so each block sees untouched input
        // even though the rows above it have already been overwritten with output.
        var carried = above
        var y = bandFrom
        while (y < bandTo) {
            val rows = min(BLOCK_ROWS, bandTo - y)
            val topPad = carried.size / w
            val wantBelow = min(PAD, grid.height - (y + rows))
            val window = IntArray((topPad + rows + wantBelow) * w)
            System.arraycopy(carried, 0, window, 0, carried.size)

            val coreAndBelow = IntArray((rows + wantBelow) * w)
            val inBand = min(rows + wantBelow, bandTo - y)
            readLocked(grid, y, inBand, coreAndBelow)
            if (inBand < rows + wantBelow) {
                // The tail of the context lies in the next band: use the saved originals.
                System.arraycopy(below, 0, coreAndBelow, inBand * w, (rows + wantBelow - inBand) * w)
            }
            System.arraycopy(coreAndBelow, 0, window, carried.size, coreAndBelow.size)

            // Save this block's last rows before they are overwritten.
            val keep = min(PAD, rows)
            val nextCarried = IntArray(keep * w)
            System.arraycopy(coreAndBelow, (rows - keep) * w, nextCarried, 0, keep * w)

            val out = enhanceWindow(window, w, topPad + rows + wantBelow, y - topPad, topPad, rows, paper, black, kernel)
            writeLocked(grid, y, rows, out)

            carried = nextCarried
            y += rows
        }
    }

    /** Enhance rows [coreTop, coreTop+coreRows) of [window], whose first row is image row [imageY]. */
    private fun enhanceWindow(
        window: IntArray,
        w: Int,
        rows: Int,
        imageY: Int,
        coreTop: Int,
        coreRows: Int,
        paper: PaperMap,
        black: Float,
        kernel: FloatArray
    ): IntArray {
        val n = w * rows
        val luma = FloatArray(n)
        val cr = FloatArray(n)
        val cb = FloatArray(n)
        val shade = FloatArray(3)
        for (row in 0 until rows) {
            for (x in 0 until w) {
                val i = row * w + x
                val p = window[i]
                paper.at(x, imageY + row, shade)
                val r = min(1.5f, ((p shr 16) and 0xFF) / 255f / shade[0])
                val g = min(1.5f, ((p shr 8) and 0xFF) / 255f / shade[1])
                val b = min(1.5f, (p and 0xFF) / 255f / shade[2])
                val yy = 0.299f * r + 0.587f * g + 0.114f * b
                luma[i] = yy
                cr[i] = r - yy
                cb[i] = b - yy
            }
        }

        val soft = convolve(luma, w, rows, kernel)
        boxBlurTwice(cr, w, rows, CHROMA_BOX_RADIUS)
        boxBlurTwice(cb, w, rows, CHROMA_BOX_RADIUS)

        val out = IntArray(coreRows * w)
        val span = WHITE_POINT - black
        for (row in 0 until coreRows) {
            val base = (coreTop + row) * w
            for (x in 0 until w) {
                val i = base + x
                val sharp = luma[i] + UNSHARP_AMOUNT * (luma[i] - soft[i])
                val level = ((sharp - black) / span).coerceIn(0f, 1f)

                // Colour fades to nothing as the pixel approaches white paper.
                val t = ((level - 0.80f) / 0.18f).coerceIn(0f, 1f)
                val colour = CHROMA_GAIN * (1f - t * t * (3f - 2f * t))
                val r = level + cr[i] * colour
                val b = level + cb[i] * colour
                val g = (level - 0.299f * r - 0.114f * b) / 0.587f

                out[row * w + x] = (window[i] and -0x1000000) or
                    (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
            }
        }
        return out
    }

    private fun to8(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    /**
     * The brightness that should become black, judged from the ink itself.
     *
     * Not from the darkest pixels on the page: a logo, a stamp or a photo supplies true
     * blacks, and taking those as "ink" would leave the grey print beside them untouched.
     * Instead look only at pixels that are neither paper nor already black, which on a
     * document means print and its soft edges, and take the dark end of that population.
     * Faint, camera-softened print (strokes that only reach mid-grey) then gets pulled
     * down hard, while print that is already dark is merely firmed up. Capped so that
     * mid-tones are never pushed past recognition, and zero when there is no such
     * population at all (a blank page).
     */
    private fun blackPoint(grid: PixelGrid, paper: PaperMap): Float {
        val w = grid.width
        val hist = IntArray(256)
        var total = 0
        val row = IntArray(w)
        val shade = FloatArray(3)
        var y = 0
        while (y < grid.height) {
            readLocked(grid, y, 1, row)
            var x = 0
            while (x < w) {
                val p = row[x]
                paper.at(x, y, shade)
                val yy = 0.299f * ((p shr 16) and 0xFF) / 255f / shade[0] +
                    0.587f * ((p shr 8) and 0xFF) / 255f / shade[1] +
                    0.114f * (p and 0xFF) / 255f / shade[2]
                hist[(yy.coerceIn(0f, 1f) * 255f).toInt()]++
                total++
                x += 4
            }
            y += 4
        }
        val from = (INK_BAND_LOW * 255).toInt()
        val to = (INK_BAND_HIGH * 255).toInt()
        var inkPixels = 0
        for (bin in from..to) inkPixels += hist[bin]
        if (inkPixels < total / 500) return 0f

        var seen = 0
        var bin = from
        while (bin < to && seen + hist[bin] < inkPixels / 10) seen += hist[bin++]
        return min(MAX_BLACK_POINT, bin / 255f * 0.9f)
    }

    private fun gaussianKernel(sigma: Float): FloatArray {
        val radius = max(1, (sigma * 2.5f + 0.5f).toInt())
        val k = FloatArray(2 * radius + 1) { i ->
            val x = (i - radius).toFloat()
            exp(-(x * x) / (2f * sigma * sigma))
        }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        return k
    }

    /** Separable convolution with edge clamping. */
    private fun convolve(src: FloatArray, w: Int, h: Int, kernel: FloatArray): FloatArray {
        val r = kernel.size / 2
        val tmp = FloatArray(src.size)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                var sum = 0f
                for (k in -r..r) sum += kernel[k + r] * src[base + (x + k).coerceIn(0, w - 1)]
                tmp[base + x] = sum
            }
        }
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                for (k in -r..r) sum += kernel[k + r] * tmp[(y + k).coerceIn(0, h - 1) * w + x]
                out[y * w + x] = sum
            }
        }
        return out
    }

    /** Two box blurs in each direction, in place: close to a Gaussian, at running-sum cost. */
    private fun boxBlurTwice(a: FloatArray, w: Int, h: Int, r: Int) {
        val line = FloatArray(max(w, h))
        repeat(2) {
            for (y in 0 until h) {
                for (x in 0 until w) line[x] = a[y * w + x]
                boxLine(line, w, r) { x, v -> a[y * w + x] = v }
            }
            for (x in 0 until w) {
                for (y in 0 until h) line[y] = a[y * w + x]
                boxLine(line, h, r) { y, v -> a[y * w + x] = v }
            }
        }
    }

    private inline fun boxLine(line: FloatArray, n: Int, r: Int, put: (Int, Float) -> Unit) {
        var sum = 0f
        for (k in -r..r) sum += line[k.coerceIn(0, n - 1)]
        val size = (2 * r + 1).toFloat()
        for (i in 0 until n) {
            put(i, sum / size)
            sum += line[(i + r + 1).coerceIn(0, n - 1)] - line[(i - r).coerceIn(0, n - 1)]
        }
    }

    /** The bare paper's colour across the page, held small and sampled bilinearly. */
    internal class PaperMap(private val w: Int, private val h: Int, private val planes: Array<FloatArray>, private val scale: Float) {

        fun at(x: Int, y: Int, into: FloatArray) {
            val fx = ((x + 0.5f) / scale - 0.5f).coerceIn(0f, (w - 1).toFloat())
            val fy = ((y + 0.5f) / scale - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val x0 = fx.toInt()
            val y0 = fy.toInt()
            val x1 = min(x0 + 1, w - 1)
            val y1 = min(y0 + 1, h - 1)
            val tx = fx - x0
            val ty = fy - y0
            for (c in 0 until 3) {
                val p = planes[c]
                val top = p[y0 * w + x0] * (1 - tx) + p[y0 * w + x1] * tx
                val bottom = p[y1 * w + x0] * (1 - tx) + p[y1 * w + x1] * tx
                into[c] = max(0.05f, top * (1 - ty) + bottom * ty)
            }
        }

        companion object {
            fun build(grid: PixelGrid): PaperMap {
                val scale = max(1f, max(grid.width, grid.height) / MAP_MAX_SIDE.toFloat())
                val w = max(1, (grid.width / scale).toInt())
                val h = max(1, (grid.height / scale).toInt())
                val sums = Array(3) { FloatArray(w * h) }
                val counts = IntArray(w * h)
                val row = IntArray(grid.width)
                for (y in 0 until grid.height) {
                    synchronized(grid) { grid.readRows(y, 1, row) }
                    val cy = min(h - 1, (y / scale).toInt())
                    for (x in 0 until grid.width) {
                        val cell = cy * w + min(w - 1, (x / scale).toInt())
                        val p = row[x]
                        sums[0][cell] += ((p shr 16) and 0xFF) / 255f
                        sums[1][cell] += ((p shr 8) and 0xFF) / 255f
                        sums[2][cell] += (p and 0xFF) / 255f
                        counts[cell]++
                    }
                }
                val shades = Array(3) { c ->
                    val mean = FloatArray(w * h) { i -> if (counts[i] > 0) sums[c][i] / counts[i] else 1f }
                    dilate(mean, w, h, DILATE_RADIUS)
                }
                // Dilation only removes things smaller than its window. Inside a photo, a
                // logo or a solid header the estimate would be that area itself, and
                // dividing by it would bleach it. Real shading rarely halves the light or
                // changes paper's colour much, so anything much darker than the page's
                // typical paper, or clearly of another colour, is not paper, and the
                // typical paper stands in for it. Judged on all three colours together,
                // so a coloured area is kept or replaced whole. This happens *before*
                // smoothing: done after, the blend from real paper to the other value
                // survives as a band of intermediate estimates, and a solid colour block
                // comes out with a glowing centre, or a bright coloured one bleached with
                // a coloured rim.
                val typical = FloatArray(3) { c -> shades[c].sorted()[shades[c].size * 3 / 4] }
                val typicalSum = typical.sum()
                for (i in 0 until w * h) {
                    val sum = shades[0][i] + shades[1][i] + shades[2][i]
                    val dark = (0 until 3).any { c -> shades[c][i] < PAPER_FLOOR * typical[c] }
                    val tinted = sum > 0f && typicalSum > 0f && (0 until 3).any { c ->
                        abs(shades[c][i] / sum - typical[c] / typicalSum) > PAPER_TINT_TOLERANCE
                    }
                    if (dark || tinted) for (c in 0 until 3) shades[c][i] = typical[c]
                }
                val planes = Array(3) { c -> smooth(shades[c], w, h) }
                return PaperMap(w, h, planes, scale)
            }

            /** Grey dilation: each cell takes the brightest value nearby, so ink vanishes. */
            private fun dilate(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
                val tmp = FloatArray(src.size)
                for (y in 0 until h) for (x in 0 until w) {
                    var m = 0f
                    for (k in -r..r) m = max(m, src[y * w + (x + k).coerceIn(0, w - 1)])
                    tmp[y * w + x] = m
                }
                val out = FloatArray(src.size)
                for (y in 0 until h) for (x in 0 until w) {
                    var m = 0f
                    for (k in -r..r) m = max(m, tmp[(y + k).coerceIn(0, h - 1) * w + x])
                    out[y * w + x] = m
                }
                return out
            }

            private fun smooth(src: FloatArray, w: Int, h: Int): FloatArray {
                DocumentEnhancer.boxBlurTwice(src, w, h, 2)
                return src
            }
        }
    }
}
