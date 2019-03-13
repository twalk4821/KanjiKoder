package tylerwalker.io.kanjireader

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.DragEvent
import android.view.View
import kotlin.math.roundToInt
import android.content.ClipData
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat.startDragAndDrop
import android.util.DisplayMetrics
import android.view.MotionEvent


class SlidingWindow(context: Context, attrs: AttributeSet): View(context, attrs) {
    companion object {
        private const val RECT_SIZE = 40F
    }

    private val rect = RectF()

    var decodeSize = PointF(1F, 1F)
        set(value) {
            field = value
            invalidate()
        }
    var previewSize = PointF(1F,1F)
        set(value) {
            field = value
            invalidate()
        }
    var screenSize = PointF(1F,1F)
        set(value) {
            field = value
            invalidate()
        }

    private fun PointF.toPreviewPixels() = PointF(x * (previewSize.x / decodeSize.x), y * (previewSize.y / decodeSize.y))
    private fun PointF.toDecodePixels() = PointF(x * (decodeSize.x / previewSize.x), y * (decodeSize.y / previewSize.y))
    private fun PointF.toScreenPixels() = PointF(x * (screenSize.x / previewSize.x), y * (screenSize.y / previewSize.y))
    private fun PointF.screenPixelsToPreviewPixels() = PointF(x * (previewSize.x / screenSize.x), y * (previewSize.y / screenSize.y))
    private fun PointF.decodePixelsToScreen() = PointF(x * (screenSize.x / decodeSize.x), y * (screenSize.y / decodeSize.y))
    private fun PointF.screenPixelsToDecode() = PointF(x * (previewSize.x / decodeSize.x), y * (previewSize.y / decodeSize.y))


    private fun PointF.snapToDecodeGrid(): PointF =
            screenPixelsToPreviewPixels()
                    .toDecodePixels().run {
                        var decodeX = x.roundToInt().toFloat()
                        var decodeY = y.roundToInt().toFloat()

                        if (decodeX < 0) decodeX = 0F
                        if (decodeY < 0) decodeY = 0F
                        if (decodeX >= decodeSize.x - RECT_SIZE) decodeX = decodeSize.x - RECT_SIZE - 1
                        if (decodeY >= decodeSize.y - RECT_SIZE) decodeY = decodeSize.y - RECT_SIZE - 1

                        log("decode position: $decodeX, $decodeY")

                        x = decodeX
                        y = decodeY

                        toPreviewPixels()
                                .toScreenPixels()
            }

    private fun getDrawnSize(): PointF =
        PointF(RECT_SIZE, RECT_SIZE).toPreviewPixels().toScreenPixels()

    fun getDecodeRect(): RectF = rect.apply {
        val decodePosition = drawPosition.screenPixelsToPreviewPixels().toDecodePixels()
        val decodeSize = PointF(RECT_SIZE, RECT_SIZE)

        left = decodePosition.x
        top = decodePosition.y
        right = left + decodeSize.x
        bottom = top + decodeSize.y
    }

    val foregroundPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.semi_transparent_foreground)
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    }

    val backgroundPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.semi_transparent_background)
        style = Paint.Style.FILL
    }

    val strokePaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.white)
        style = Paint.Style.STROKE
        strokeWidth = 6F
    }

    private var startingPosition = PointF(0F, 0F)
    private var dragStart = PointF(0F, 0F)
    private var drawPosition = PointF(0F, 0F)
        set(value) {
            field = value
            invalidate()
        }

    init {
        setOnLongClickListener {
            val data = ClipData.newPlainText("", "")
            val shadowBuilder = TransparentShadowBuilder()
            startDragAndDrop(data, shadowBuilder, it, 0)
            true
        }
    }

    override fun onDragEvent(event: DragEvent): Boolean =
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                startingPosition.x = drawPosition.x
                startingPosition.y = drawPosition.y

                dragStart.x = event.x
                dragStart.y = event.y

                true
            }
            DragEvent.ACTION_DROP -> {
                val translation = PointF(
                        event.x - dragStart.x,
                        event.y - dragStart.y)

                drawPosition.x = startingPosition.x + translation.x
                drawPosition.y = startingPosition.y + translation.y

                drawPosition = drawPosition.snapToDecodeGrid()

                true
            }
            DragEvent.ACTION_DRAG_LOCATION -> {
                val translation = PointF(
                        event.x - dragStart.x,
                        event.y - dragStart.y)

                drawPosition.x = startingPosition.x + translation.x
                drawPosition.y = startingPosition.y + translation.y

                drawPosition = drawPosition.snapToDecodeGrid()

                true
            }
            else -> super.onDragEvent(event)
        }

    override fun onDraw(canvas: Canvas) {
        val drawSize = getDrawnSize()

        with (canvas) {
            save()

            // draw semi opaque background
            drawRect(0F, 0F, screenSize.x, screenSize.y, backgroundPaint)

            // draw relatively transparent foreground
            translate(drawPosition.x, drawPosition.y)
            drawRect(0F, 0F, drawSize.x, drawSize.y, foregroundPaint)

            // draw center cross
            val cx = drawSize.x / 2F
            val cy = drawSize.y / 2F
            val size = 30F

            drawLine(cx - size, cy, cx + size, cy, strokePaint)
            drawLine(cx, cy - size, cx, cy + size, strokePaint)

            // draw corners
            // top left
            drawLine(0F, 0F, 0F, 1.5F * size, strokePaint)
            drawLine(0F, 0F, 1.5F * size, 0F, strokePaint)

            // top right
            drawLine(drawSize.x, 0F, drawSize.x, 1.5F * size, strokePaint)
            drawLine(drawSize.x - 1.5F * size, 0F, drawSize.x, 0F, strokePaint)

            // bottom left
            drawLine(0F, drawSize.y - 1.5F * size, 0F, drawSize.y, strokePaint)
            drawLine(0F, drawSize.y, 1.5F * size, drawSize.y, strokePaint)

            // bottom right
            drawLine(drawSize.x - 1.5F * size, drawSize.y, drawSize.x, drawSize.y, strokePaint)
            drawLine(drawSize.x, drawSize.y, drawSize.x, drawSize.y - 1.5F * size, strokePaint)

            restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        super.onTouchEvent(event)
        return false
    }

    fun initRectPosition() {
        drawPosition = drawPosition.apply {
            x = screenSize.x / 2F
            y = screenSize.y / 2F
        }.run {
            screenPixelsToPreviewPixels()
                    .toDecodePixels()
        }.run {
            x -= (RECT_SIZE / 2F)
            y -= (RECT_SIZE)

            toPreviewPixels()
                    .toScreenPixels()
        }
    }

    private fun log(message: String) {
        Log.d("SlidingWindow", message)
    }

    inner class TransparentShadowBuilder : View.DragShadowBuilder() {

        override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
            outShadowSize.set(1, 1)
            outShadowTouchPoint.set(0, 0)
        }
    }
}