package com.zakius.caravanserai

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import androidx.core.graphics.PathParser
import java.io.File
import kotlin.math.roundToInt

/**
 * Pure Android View that renders Frame 1 of Home pixel-faithfully without loading Compose Runtime,
 * Compose UI, Recomposer, or Snapshot state before the first frame is submitted.
 *
 * On startup-bg, the static top band (title + header + body) and 48 dp bottom NavBar band are
 * packed vertically into ONE Bitmap.Config.HARDWARE atlas off-thread before doFrame(), so Frame 1
 * onDraw() issues only drawColor + 2 hardware drawBitmap calls + 1 gesture-strip rect — avoiding
 * AtlasTextOp glyph upload and CircularRRectOp pipeline creation on the critical path.
 */
internal class ColdStartHomeView(
    context: Context,
    prefs: SharedPreferences,
    private val isYouTube: Boolean,
    private val signedIn: Boolean,
    private val hasQueue: Boolean,
) : View(context) {

    var pendingTab: Int? = null
        private set
    var pendingSignIn: Boolean = false
        private set

    private val dm = resources.displayMetrics
    private val density = dm.density
    private val fontScale = resources.configuration.fontScale
    private val fixedSpDensity = density * fontScale.coerceAtMost(1.3f)
    private val spDensity = density * fontScale

    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val trueBlack = dark && prefs.getBoolean("ui.trueBlack", false)

    private var bands: PrewarmedBands? = prewarmed?.takeIf {
        it.matches(dark, trueBlack, isYouTube, signedIn, hasQueue, density, fontScale)
    }

    private val palette: Palette = bands?.palette ?: resolvePalette(context, dark, trueBlack)

    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0

    private val fillPaint = Paint()
    private val signInRect = RectF()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private var fallbackPaints: FallbackPaints? = null

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= 30) {
            val safe = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
            val nav = insets.getInsets(WindowInsets.Type.navigationBars())
            insetTop = safe.top
            insetLeft = safe.left
            insetRight = safe.right
            insetBottom = nav.bottom
        } else {
            @Suppress("DEPRECATION")
            insetTop = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            insetBottom = insets.systemWindowInsetBottom
            @Suppress("DEPRECATION")
            insetLeft = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            insetRight = insets.systemWindowInsetRight
        }
        invalidate()
        return insets
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        // Re-check prewarmed bands in case startup-bg finished between <init> and onDraw.
        val b = bands ?: prewarmed?.takeIf {
            it.matches(dark, trueBlack, isYouTube, signedIn, hasQueue, density, fontScale)
        }?.also { bands = it }

        if (b != null && insetLeft == 0 && insetRight == 0 && b.width == w && !b.atlas.isRecycled) {
            canvas.drawColor(palette.bg)
            srcRect.set(0, 0, w, b.topH)
            dstRect.set(0f, insetTop.toFloat(), w.toFloat(), (insetTop + b.topH).toFloat())
            canvas.drawBitmap(b.atlas, srcRect, dstRect, null)
            val navTop = (h - insetBottom - b.navH).toFloat()
            srcRect.set(0, b.topH, w, b.topH + b.navH)
            dstRect.set(0f, navTop, w.toFloat(), navTop + b.navH)
            canvas.drawBitmap(b.atlas, srcRect, dstRect, null)
            if (insetBottom > 0) {
                fillPaint.color = palette.surfaceContainer
                canvas.drawRect(0f, h - insetBottom.toFloat(), w.toFloat(), h.toFloat(), fillPaint)
            }
            if (!b.signInRectInTop.isEmpty) {
                signInRect.set(b.signInRectInTop)
                signInRect.offset(0f, insetTop.toFloat())
            } else {
                signInRect.setEmpty()
            }
            return
        }

        // Fallback direct drawing (e.g., split-screen / rotated / prewarm not ready yet).
        val fp = fallbackPaints ?: FallbackPaints(palette, density, spDensity, fixedSpDensity).also { fallbackPaints = it }
        canvas.drawColor(palette.bg)
        val contentLeft = insetLeft.toFloat()
        val contentWidth = (w - insetLeft - insetRight).coerceAtLeast(1).toFloat()
        canvas.save()
        canvas.translate(contentLeft, insetTop.toFloat())
        val localSignIn = RectF()
        drawTopContent(canvas, contentWidth, isYouTube, signedIn, palette, fp, localSignIn)
        canvas.restore()
        if (!localSignIn.isEmpty) {
            signInRect.set(localSignIn)
            signInRect.offset(contentLeft, insetTop.toFloat())
        } else {
            signInRect.setEmpty()
        }

        val navRowH = (48f * density).roundToInt()
        val navTop = (h - insetBottom - navRowH).toFloat()
        fillPaint.color = palette.surfaceContainer
        canvas.drawRect(0f, navTop, w.toFloat(), h.toFloat(), fillPaint)
        canvas.save()
        canvas.translate(contentLeft, navTop)
        drawNavContent(canvas, contentWidth, navRowH.toFloat(), hasQueue, palette, fp)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y
            val navTop = height - insetBottom - 48f * density
            if (y >= navTop) {
                val colW = (width - insetLeft - insetRight).coerceAtLeast(1) / 5f
                val col = ((x - insetLeft) / colW).toInt().coerceIn(0, 4)
                pendingTab = when (col) {
                    0 -> TAB_HOME
                    1 -> TAB_SEARCH
                    2 -> if (hasQueue) TAB_PLAYER else null
                    3 -> TAB_LIBRARY
                    else -> TAB_SETTINGS
                }
            } else if (!signInRect.isEmpty && signInRect.contains(x, y)) {
                pendingSignIn = true
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        val b = prewarmed
        prewarmed = null
        bands = null
        // Delay recycle by one message so any in-flight RenderThread frame referencing the hardware bitmap completes.
        if (b != null) {
            post {
                if (!b.atlas.isRecycled) b.atlas.recycle()
            }
        }
    }

    private class Palette(
        val bg: Int,
        val surfaceContainer: Int,
        val primary: Int,
        val onPrimary: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val secondaryContainer: Int,
        val onSecondaryContainer: Int,
    )

    private class FallbackPaints(
        palette: Palette,
        val density: Float,
        val spDensity: Float,
        val fixedSpDensity: Float,
    ) {
        val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.onSurface
            textSize = 22f * spDensity
            typeface = Typeface.DEFAULT
        }
        val header = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.primary
            textSize = 14f * spDensity
            letterSpacing = 0.1f / 14f
            typeface = medium
        }
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.onSurface
            textSize = 16f * spDensity
            letterSpacing = 0.5f / 16f
            typeface = Typeface.DEFAULT
        }
        val btnText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.onPrimary
            textSize = 14f * spDensity
            letterSpacing = 0.1f / 14f
            typeface = medium
            textAlign = Paint.Align.CENTER
        }
        val navText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f * fixedSpDensity
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
        }
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.onSurface
            textSize = 12f * spDensity
            letterSpacing = 0.4f / 12f
            typeface = Typeface.DEFAULT
        }
    }

    private class PrewarmedBands(
        val atlas: Bitmap,
        val topH: Int,
        val navH: Int,
        val width: Int,
        val signInRectInTop: RectF,
        val palette: Palette,
        val dark: Boolean,
        val trueBlack: Boolean,
        val isYouTube: Boolean,
        val signedIn: Boolean,
        val hasQueue: Boolean,
        val density: Float,
        val fontScale: Float,
    ) {
        fun matches(
            dark: Boolean,
            trueBlack: Boolean,
            yt: Boolean,
            signedIn: Boolean,
            hasQueue: Boolean,
            density: Float,
            fontScale: Float,
        ) = this.dark == dark &&
            this.trueBlack == trueBlack &&
            this.isYouTube == yt &&
            this.signedIn == signedIn &&
            this.hasQueue == hasQueue &&
            this.density == density &&
            this.fontScale == fontScale
    }

    companion object {
        @Volatile private var prewarmed: PrewarmedBands? = null

        private val NAV_LABELS = arrayOf("Home", "Search", "Player", "Library", "Settings")

        private const val PATH_HOME = "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z"
        private const val PATH_SEARCH = "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"
        private const val PATH_PLAYER = "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,16.5c-2.49,0 -4.5,-2.01 -4.5,-4.5S9.51,7.5 12,7.5s4.5,2.01 4.5,4.5 -2.01,4.5 -4.5,4.5zM12,11c-0.55,0 -1,0.45 -1,1s0.45,1 1,1 1,-0.45 1,-1 -0.45,-1 -1,-1z"
        private const val PATH_LIBRARY = "M20,2H8c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM18,7h-3v5.5c0,1.38 -1.12,2.5 -2.5,2.5S10,13.88 10,12.5s1.12,-2.5 2.5,-2.5c0.57,0 1.08,0.19 1.5,0.51V5h4v2zM4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6z"
        private const val PATH_SETTINGS = "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94 0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6 3.6,1.62 3.6,3.6 -1.62,3.6 -3.6,3.6z"
        private const val PATH_THUMB_UP = "M1,21h4V9H1v12zM23,10c0,-1.1 -0.9,-2 -2,-2h-6.31l0.95,-4.57 0.03,-0.32c0,-0.41 -0.17,-0.79 -0.44,-1.06L14.17,1 7.59,7.59C7.22,7.95 7,8.45 7,9v10c0,1.1 0.9,2 2,2h9c0.83,0 1.54,-0.5 1.84,-1.22l3.02,-7.05c0.09,-0.23 0.14,-0.47 0.14,-0.73v-2z"
        private const val PATH_STAR = "M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z"
        private const val PATH_SHUFFLE = "M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.42,-1.41zM14.5,4l2.04,2.04L4,18.59 5.41,20 17.96,7.46 20,9.5V4h-5.5zm0.33,9.41l-1.41,1.41 3.13,3.13L14.5,20H20v-5.5l-2.04,2.04 -3.13,-3.13z"

        /** Pre-renders both static Home bands into a single HARDWARE atlas off the main thread. */
        fun prewarm(context: Context, prefs: SharedPreferences) {
            if (!prefs.getBoolean("setup.done", false) || prefs.getInt("ui.tab", TAB_HOME) != TAB_HOME) {
                // Still warm system fonts for Setup/Player screens.
                val p = Paint()
                p.measureText("Home")
                p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                p.measureText("Your mixes")
                return
            }
            val dm = context.resources.displayMetrics
            val w = dm.widthPixels
            if (w <= 0) return
            val density = dm.density
            val fontScale = context.resources.configuration.fontScale
            val spDensity = density * fontScale
            val fixedSpDensity = density * fontScale.coerceAtMost(1.3f)
            val isYouTube = BuildConfig.SLUG == "dragoman"
            val signedIn = isYouTube && prefs.getString("cookie", null)?.contains("SAPISID") == true
            val hasQueue = hasSavedQueue(context.filesDir)
            val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val trueBlack = dark && prefs.getBoolean("ui.trueBlack", false)
            val pal = resolvePalette(context, dark, trueBlack)
            val fp = FallbackPaints(pal, density, spDensity, fixedSpDensity)

            // Pack top band + 48 dp NavBar band vertically into ONE hardware bitmap (single gralloc alloc + upload).
            val topH = measureTopHeight(w.toFloat(), isYouTube, signedIn, fp).coerceAtLeast(1)
            val navH = (48f * density).roundToInt().coerceAtLeast(1)
            val atlasSw = Bitmap.createBitmap(w, topH + navH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(atlasSw)
            canvas.drawColor(pal.bg)
            val signInRect = RectF()
            drawTopContent(canvas, w.toFloat(), isYouTube, signedIn, pal, fp, signInRect)

            fp.fill.color = pal.surfaceContainer
            canvas.drawRect(0f, topH.toFloat(), w.toFloat(), (topH + navH).toFloat(), fp.fill)
            canvas.save()
            canvas.translate(0f, topH.toFloat())
            drawNavContent(canvas, w.toFloat(), navH.toFloat(), hasQueue, pal, fp)
            canvas.restore()

            val atlasHw = atlasSw.copy(Bitmap.Config.HARDWARE, false)?.also { atlasSw.recycle() } ?: atlasSw

            prewarmed = PrewarmedBands(
                atlas = atlasHw,
                topH = topH,
                navH = navH,
                width = w,
                signInRectInTop = signInRect,
                palette = pal,
                dark = dark,
                trueBlack = trueBlack,
                isYouTube = isYouTube,
                signedIn = signedIn,
                hasQueue = hasQueue,
                density = density,
                fontScale = fontScale,
            )
        }

        private fun measureTopHeight(contentWidth: Float, isYouTube: Boolean, signedIn: Boolean, fp: FallbackPaints): Int {
            val d = fp.density
            val sp = fp.spDensity
            var y = 12f * d + 28f * sp + 4f * d + 16f * d + 20f * sp + 4f * d
            if (isYouTube && !signedIn) {
                val pad = 24f * d
                val textW = (contentWidth - pad * 2f).coerceAtLeast(1f)
                val msg = "Sign in to YouTube Music to see your mixes."
                val singleW = fp.body.measureText(msg)
                val textH = if (singleW <= textW) {
                    24f * sp
                } else {
                    StaticLayout.Builder.obtain(msg, 0, msg.length, fp.body, textW.roundToInt().coerceAtLeast(1))
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setIncludePad(false)
                        .build().height.toFloat()
                }
                val btnH = maxOf(40f * d, 20f * sp + 20f * d)
                y += pad + textH + 12f * d + btnH + 8f * d
            } else if (isYouTube) {
                val widthDp = (contentWidth / d).toInt()
                val sideDp = (widthDp - 56) / 3
                val sidePx = (sideDp * d).coerceAtLeast(64f * d)
                y += 8f * d + sidePx + 4f * d + 16f * sp * 3f + 8f * d
            } else {
                y += 8f * d + 56f * d + 8f * d
            }
            return y.roundToInt() + 2
        }

        private fun drawTopContent(
            canvas: Canvas,
            contentWidth: Float,
            isYouTube: Boolean,
            signedIn: Boolean,
            pal: Palette,
            fp: FallbackPaints,
            outSignInRect: RectF,
        ) {
            val d = fp.density
            val sp = fp.spDensity
            var y = 0f

            // ScreenTitle("Home")
            val titleLineH = 28f * sp
            y += 12f * d
            drawCenteredInLine(canvas, "Home", 16f * d, y, titleLineH, fp.title)
            y += titleLineH + 4f * d

            // Header("Your mixes" / "Your library")
            val headerLineH = 20f * sp
            y += 16f * d
            drawCenteredInLine(canvas, if (isYouTube) "Your mixes" else "Your library", 16f * d, y, headerLineH, fp.header)
            y += headerLineH + 4f * d

            outSignInRect.setEmpty()
            if (isYouTube && !signedIn) {
                val pad = 24f * d
                val textW = (contentWidth - pad * 2f).coerceAtLeast(1f)
                val msg = "Sign in to YouTube Music to see your mixes."
                y += pad
                val singleW = fp.body.measureText(msg)
                if (singleW <= textW) {
                    val lineH = 24f * sp
                    drawCenteredInLine(canvas, msg, (contentWidth - singleW) / 2f, y, lineH, fp.body)
                    y += lineH + 12f * d
                } else {
                    val sl = StaticLayout.Builder.obtain(msg, 0, msg.length, fp.body, textW.roundToInt().coerceAtLeast(1))
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setIncludePad(false)
                        .build()
                    canvas.save()
                    canvas.translate(pad, y)
                    sl.draw(canvas)
                    canvas.restore()
                    y += sl.height + 12f * d
                }
                val btnLabel = "Sign in"
                val btnW = fp.btnText.measureText(btnLabel) + 48f * d
                val btnH = maxOf(40f * d, 20f * sp + 20f * d)
                val btnLeft = (contentWidth - btnW) / 2f
                outSignInRect.set(btnLeft, y, btnLeft + btnW, y + btnH)
                fp.fill.color = pal.primary
                canvas.drawRoundRect(outSignInRect, btnH / 2f, btnH / 2f, fp.fill)
                val fm = fp.btnText.fontMetrics
                canvas.drawText(btnLabel, outSignInRect.centerX(), outSignInRect.centerY() - (fm.ascent + fm.descent) / 2f, fp.btnText)
            } else if (isYouTube) {
                val widthDp = (contentWidth / d).toInt()
                val sideDp = (widthDp - 56) / 3
                val sidePx = (sideDp * d).coerceAtLeast(64f * d)
                val tileLeft = 16f * d
                val tileTop = y + 8f * d
                val rect = RectF(tileLeft, tileTop, tileLeft + sidePx, tileTop + sidePx)
                fp.fill.color = pal.secondaryContainer
                val r = 8f * d
                canvas.drawRoundRect(rect, r, r, fp.fill)
                val iconSize = sidePx / 3f
                drawVectorPath(canvas, PATH_THUMB_UP, rect.centerX() - iconSize / 2f, rect.centerY() - iconSize / 2f, iconSize, pal.onSecondaryContainer, fp.fill)
                drawCenteredInLine(canvas, "Liked music", tileLeft, tileTop + sidePx + 4f * d, 16f * sp, fp.small)
            } else {
                val gap = 12f * d
                val btnW = (contentWidth - 32f * d - gap) / 2f
                val btnH = 56f * d
                val top = y + 8f * d
                val r = 8f * d
                val labels = arrayOf("Starred", "Shuffle all")
                val labelPaint = TextPaint(fp.header).apply { color = pal.onSecondaryContainer }
                val fm = labelPaint.fontMetrics
                val iconSize = 24f * d
                for (i in 0..1) {
                    val left = 16f * d + i * (btnW + gap)
                    val rect = RectF(left, top, left + btnW, top + btnH)
                    fp.fill.color = pal.secondaryContainer
                    canvas.drawRoundRect(rect, r, r, fp.fill)
                    val iconPath = if (i == 0) PATH_STAR else PATH_SHUFFLE
                    drawVectorPath(canvas, iconPath, left + 16f * d, rect.centerY() - iconSize / 2f, iconSize, pal.onSecondaryContainer, fp.fill)
                    canvas.drawText(labels[i], left + 52f * d, rect.centerY() - (fm.ascent + fm.descent) / 2f, labelPaint)
                }
            }
        }

        private fun drawNavContent(
            canvas: Canvas,
            contentWidth: Float,
            navRowH: Float,
            hasQueue: Boolean,
            pal: Palette,
            fp: FallbackPaints,
        ) {
            val iconSize = 22f * fp.density
            val labelLineH = 12f * fp.fixedSpDensity
            val contentH = iconSize + labelLineH
            val iconTop = (navRowH - contentH) / 2f
            val labelTop = iconTop + iconSize
            val fm = fp.navText.fontMetrics
            val labelBaseline = labelTop + labelLineH / 2f - (fm.ascent + fm.descent) / 2f
            val colW = contentWidth / 5f
            val paths = arrayOf(PATH_HOME, PATH_SEARCH, PATH_PLAYER, PATH_LIBRARY, PATH_SETTINGS)
            for (i in 0 until 5) {
                val cx = colW * (i + 0.5f)
                val tint = when {
                    i == 0 -> pal.primary
                    i == 2 && !hasQueue -> withAlpha(pal.onSurfaceVariant, 0.35f)
                    else -> pal.onSurfaceVariant
                }
                drawVectorPath(canvas, paths[i], cx - iconSize / 2f, iconTop, iconSize, tint, fp.fill)
                fp.navText.color = tint
                canvas.drawText(NAV_LABELS[i], cx, labelBaseline, fp.navText)
            }
        }

        private fun drawVectorPath(canvas: Canvas, data: String, x: Float, y: Float, sizePx: Float, color: Int, paint: Paint) {
            val scale = sizePx / 24f
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(scale, scale)
            paint.color = color
            val path: Path = PathParser.createPathFromPathData(data)
            canvas.drawPath(path, paint)
            canvas.restore()
        }

        private fun drawCenteredInLine(canvas: Canvas, text: String, x: Float, lineTop: Float, lineH: Float, paint: TextPaint) {
            val fm = paint.fontMetrics
            val baseline = lineTop + lineH / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(text, x, baseline, paint)
        }

        private fun resolvePalette(context: Context, dark: Boolean, trueBlack: Boolean): Palette {
            if (Build.VERSION.SDK_INT >= 31) {
                if (dark) {
                    val n900 = context.getColor(android.R.color.system_neutral1_900)
                    val n800 = context.getColor(android.R.color.system_neutral1_800)
                    val black = context.getColor(android.R.color.system_neutral1_1000)
                    return Palette(
                        bg = if (trueBlack) 0xFF000000.toInt() else lerpArgb(n900, black, 0.4f),
                        surfaceContainer = if (trueBlack) 0xFF000000.toInt() else lerpArgb(n900, n800, 0.2f),
                        primary = context.getColor(android.R.color.system_accent1_200),
                        onPrimary = context.getColor(android.R.color.system_accent1_800),
                        onSurface = context.getColor(android.R.color.system_neutral1_100),
                        onSurfaceVariant = context.getColor(android.R.color.system_neutral2_200),
                        secondaryContainer = context.getColor(android.R.color.system_accent2_700),
                        onSecondaryContainer = context.getColor(android.R.color.system_accent2_100),
                    )
                }
                val n10 = context.getColor(android.R.color.system_neutral1_10)
                val n50 = context.getColor(android.R.color.system_neutral1_50)
                val n100 = context.getColor(android.R.color.system_neutral1_100)
                return Palette(
                    bg = lerpArgb(n10, n50, 0.25f),
                    surfaceContainer = lerpArgb(n50, n100, 0.2f),
                    primary = context.getColor(android.R.color.system_accent1_600),
                    onPrimary = context.getColor(android.R.color.system_accent1_0),
                    onSurface = context.getColor(android.R.color.system_neutral1_900),
                    onSurfaceVariant = context.getColor(android.R.color.system_neutral2_700),
                    secondaryContainer = context.getColor(android.R.color.system_accent2_100),
                    onSecondaryContainer = context.getColor(android.R.color.system_accent2_900),
                )
            }
            if (dark) {
                return Palette(
                    bg = if (trueBlack) 0xFF000000.toInt() else 0xFF141218.toInt(),
                    surfaceContainer = if (trueBlack) 0xFF000000.toInt() else 0xFF211F26.toInt(),
                    primary = 0xFFD0BCFF.toInt(),
                    onPrimary = 0xFF381E72.toInt(),
                    onSurface = 0xFFE6E0E9.toInt(),
                    onSurfaceVariant = 0xFFCAC4D0.toInt(),
                    secondaryContainer = 0xFF4A4458.toInt(),
                    onSecondaryContainer = 0xFFE8DEF8.toInt(),
                )
            }
            return Palette(
                bg = 0xFFFEF7FF.toInt(),
                surfaceContainer = 0xFFF3EDF7.toInt(),
                primary = 0xFF6750A4.toInt(),
                onPrimary = 0xFFFFFFFF.toInt(),
                onSurface = 0xFF1D1B20.toInt(),
                onSurfaceVariant = 0xFF49454F.toInt(),
                secondaryContainer = 0xFFE8DEF8.toInt(),
                onSecondaryContainer = 0xFF1D192B.toInt(),
            )
        }

        private fun withAlpha(argb: Int, alpha: Float): Int {
            val a = (((argb ushr 24) and 0xFF) * alpha).roundToInt().coerceIn(0, 255)
            return (a shl 24) or (argb and 0x00FFFFFF)
        }

        private fun lerpArgb(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int): Int {
                val va = (a ushr shift) and 0xFF
                val vb = (b ushr shift) and 0xFF
                return (va + (vb - va) * t).roundToInt().coerceIn(0, 255)
            }
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        fun hasSavedQueue(filesDir: File): Boolean = File(filesDir, "queue.json").exists()
    }
}
