package nl.houseofsneijder.duoshot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Vaste kaart-geleider over de zoeker: een afgeronde rechthoek in de
 * verhouding 63:88 (breedte:hoogte van een speelkaart), gecentreerd en zo
 * groot mogelijk met marge. Buiten de rechthoek wordt het beeld gedimd.
 *
 * [guideFraction] geeft de rechthoek terug als fractie van de view-grootte;
 * dezelfde fractie wordt op de opname toegepast (de PreviewView dekt exact
 * dezelfde oppervlakte dankzij de ViewPort in MainActivity).
 */
class CardGuideView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val guide = RectF()
    private val dimPaint = Paint().apply { color = 0x66000000 }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(2.5f)
    }
    private val path = Path()
    private var radius = 0f

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val marginX = dp(28f)
        val reservedTop = dp(96f)      // label
        val reservedBottom = dp(160f)  // sluiterknop
        val availW = w - 2 * marginX
        val availH = h - reservedTop - reservedBottom - 2 * dp(12f)

        var gw = availW
        var gh = gw * CARD_H / CARD_W
        if (gh > availH) {
            gh = availH
            gw = gh * CARD_W / CARD_H
        }
        val left = (w - gw) / 2f
        val top = reservedTop + (h - reservedTop - reservedBottom - gh) / 2f
        guide.set(left, top, left + gw, top + gh)
        radius = gw * 0.045f

        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
        path.addRoundRect(guide, radius, radius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (guide.isEmpty) return
        canvas.drawPath(path, dimPaint)
        canvas.drawRoundRect(guide, radius, radius, strokePaint)
    }

    /** Geleider als fractie (0..1) van de view: left/top/right/bottom. */
    fun guideFraction(): RectF {
        if (width == 0 || height == 0 || guide.isEmpty) return RectF(0f, 0f, 1f, 1f)
        return RectF(
            guide.left / width, guide.top / height,
            guide.right / width, guide.bottom / height
        )
    }

    companion object {
        const val CARD_W = 63f
        const val CARD_H = 88f
    }
}
