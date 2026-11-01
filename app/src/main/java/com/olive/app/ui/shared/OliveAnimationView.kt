// Safe-app users: draws the PIN-to-safe-app olive animation with an Android View.
package com.olive.app.ui.shared

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withRotation
import kotlin.math.atan2
import kotlin.math.sin

/**
 * Pure Android Canvas Custom View.
 *
 * Runs the botanical olive branch growth, olive drop, and fade away
 * without any screen or layout coupling.
 */
internal class OliveAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var onAnimationFinished: (() -> Unit)? = null
    var onOlivePositionChanged: ((x: Float, y: Float) -> Unit)? = null

    private var animator: ValueAnimator? = null

    // Paints
    private val branchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = "#5D4037".toColorInt()
    }

    private val leafPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = "#556B2F".toColorInt()
    }

    private val olivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(140, 255, 255, 255)
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = "#6B8E23".toColorInt()
    }

    private val branchPath = Path()
    private val subPath = Path()
    private val pathMeasure = PathMeasure()
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)
    private val leafFractions = floatArrayOf(0.18f, 0.32f, 0.46f, 0.60f, 0.74f, 0.88f)
    private val leafPath = Path()
    private val oliveOval = RectF()
    private val highlightOval = RectF()
    private val gradientMatrix = Matrix()
    private val oliveGradient = RadialGradient(
        -11.4f,
        -11.4f,
        53.2f,
        "#7A933B".toColorInt(),
        "#2C3E14".toColorInt(),
        Shader.TileMode.CLAMP,
    )

    fun startAnimation(durationMs: Long = 2600L) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                progress = va.animatedValue as Float
                if (progress >= 1f) {
                    onAnimationFinished?.invoke()
                }
            }
        }
        animator?.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        branchPath.reset()
        branchPath.moveTo(0f, h * 0.12f)
        branchPath.cubicTo(
            w * 0.28f, h * 0.08f,
            w * 0.65f, h * 0.24f,
            w * 1.05f, h * 0.18f
        )
        pathMeasure.setPath(branchPath, false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress <= 0f || progress >= 1f) return

        val w = width.toFloat()
        val h = height.toFloat()

        val branchGrowth = (progress / 0.38f).coerceIn(0f, 1f)
        val oliveGrowth = if (progress < 0.32f) 0f else ((progress - 0.32f) / 0.20f).coerceIn(0f, 1f)
        val oliveDrop = if (progress < 0.52f) 0f else {
            val d = ((progress - 0.52f) / 0.36f).coerceIn(0f, 1f)
            Math.pow(d.toDouble(), 1.75).toFloat()
        }
        val ripple = if (progress < 0.86f) 0f else ((progress - 0.86f) / 0.14f).coerceIn(0f, 1f)

        // Alphas: fade away as animation progresses
        val branchAlpha = if (progress < 0.50f) 1f else (1f - (progress - 0.50f) / 0.26f).coerceIn(0f, 1f)
        val oliveAlpha = if (progress < 0.58f) 1f else (1f - (progress - 0.58f) / 0.28f).coerceIn(0f, 1f)

        branchPaint.alpha = (branchAlpha * 255).toInt()
        leafPaint.alpha = (branchAlpha * 255).toInt()
        olivePaint.alpha = (oliveAlpha * 255).toInt()
        highlightPaint.alpha = (oliveAlpha * 140).toInt()

        // 1. Draw Trimmed Branch Path
        if (branchAlpha > 0.005f && branchGrowth > 0f) {
            subPath.reset()
            val totalLen = pathMeasure.length
            pathMeasure.getSegment(0f, totalLen * branchGrowth, subPath, true)
            canvas.drawPath(subPath, branchPaint)

            // 2. Draw Leaves
            for (i in leafFractions.indices) {
                val frac = leafFractions[i]
                if (branchGrowth >= frac * 0.82f) {
                    val leafProgress = ((branchGrowth - frac * 0.82f) / 0.22f).coerceIn(0f, 1f)
                    if (pathMeasure.getPosTan(totalLen * frac, pos, tan)) {
                        val baseAngle = Math.toDegrees(atan2(tan[1].toDouble(), tan[0].toDouble())).toFloat()
                        val side = if (i % 2 == 0) -1f else 1f
                        canvas.withRotation(baseAngle + side * 42f, pos[0], pos[1]) {
                            drawLeaf(canvas, pos[0], pos[1], leafProgress)
                        }
                    }
                }
            }
        }

        // 3. Draw Olive
        if (oliveGrowth > 0f && oliveAlpha > 0.005f && pathMeasure.getPosTan(pathMeasure.length * 0.68f, pos, tan)) {
            val anchorX = pos[0]
            val anchorY = pos[1]
            val baseRadius = 38f
            val budScale = oliveGrowth.coerceIn(0f, 1f)

            val oliveX: Float
            val oliveY: Float
            var rot = 12f

            if (oliveDrop == 0f) {
                oliveX = anchorX + 4f
                oliveY = anchorY + (40f * budScale)
                if (branchAlpha > 0.01f) {
                    canvas.drawLine(anchorX, anchorY, oliveX, oliveY - baseRadius * 0.6f * budScale, branchPaint)
                }
            } else {
                val dropStart = anchorY + 40f
                val floorY = h * 0.92f
                oliveX = anchorX + (sin(oliveDrop * Math.PI.toFloat() * 1.5f) * 20f)
                oliveY = dropStart + ((floorY - dropStart) * oliveDrop)
                rot = 12f + (oliveDrop * 160f)
            }

            onOlivePositionChanged?.invoke(oliveX, oliveY)

            gradientMatrix.setTranslate(oliveX, oliveY)
            oliveGradient.setLocalMatrix(gradientMatrix)
            olivePaint.shader = oliveGradient
            oliveOval.set(
                oliveX - baseRadius * 0.8f * budScale,
                oliveY - baseRadius * 1.1f * budScale,
                oliveX + baseRadius * 0.8f * budScale,
                oliveY + baseRadius * 1.1f * budScale
            )
            highlightOval.set(
                oliveX - baseRadius * 0.4f,
                oliveY - baseRadius * 0.6f,
                oliveX,
                oliveY - baseRadius * 0.1f,
            )
            canvas.withRotation(rot, oliveX, oliveY) {
                drawOval(oliveOval, olivePaint)
                drawOval(highlightOval, highlightPaint)
            }

            // 4. Ground Ripple
            if (ripple > 0f) {
                ripplePaint.alpha = ((1f - ripple) * 180 * oliveAlpha).toInt()
                canvas.drawCircle(oliveX, h * 0.92f, 120f * ripple, ripplePaint)
            }
        }
    }

    private fun drawLeaf(canvas: Canvas, x: Float, y: Float, scale: Float) {
        leafPath.reset()
        val len = 65f * scale
        val wid = 26f * scale
        leafPath.moveTo(x, y)
        leafPath.cubicTo(x + len * 0.4f, y - wid, x + len * 0.85f, y - wid * 0.6f, x + len, y)
        leafPath.cubicTo(x + len * 0.85f, y + wid * 0.6f, x + len * 0.4f, y + wid, x, y)
        canvas.drawPath(leafPath, leafPaint)
    }
}
