package nl.houseofsneijder.duoshot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Beeldbewerking zonder UI: opname bijsnijden tot de kaart-geleider en
 * voor- en achterkant naast elkaar zetten.
 *
 * Geheugen: er wordt alleen het stuk van de JPEG gedecodeerd dat binnen de
 * geleider valt (BitmapRegionDecoder) met een inSampleSize zodat de kaart
 * nooit veel groter dan [MAX_KAART_HOOGTE] in RAM komt.
 */
object KaartBeeld {

    const val MAX_KAART_HOOGTE = 2400
    const val TUSSENRUIMTE = 40
    const val JPEG_KWALITEIT = 92

    /**
     * Snijdt de opname bij.
     * @param jpeg      ruwe JPEG-bytes uit de ImageProxy
     * @param cropRect  ImageProxy.cropRect (sensor-coördinaten van de ViewPort)
     * @param rotation  imageInfo.rotationDegrees (0/90/180/270)
     * @param guide     geleider als fractie van het (geroteerde) zoekerbeeld
     */
    fun snijKaartUit(jpeg: ByteArray, cropRect: Rect, rotation: Int, guide: RectF): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val imgW = bounds.outWidth
        val imgH = bounds.outHeight

        // cropRect kan leeg zijn als er geen ViewPort was; dan het hele beeld.
        val crop = if (cropRect.isEmpty) Rect(0, 0, imgW, imgH) else Rect(cropRect).apply {
            intersect(0, 0, imgW, imgH)
        }
        val w = crop.width()
        val h = crop.height()

        // Geleider-fractie (in geroteerd beeld) terugvertalen naar
        // ongeroteerde sensor-coördinaten binnen cropRect.
        val fl = guide.left; val ft = guide.top; val fr = guide.right; val fb = guide.bottom
        val region = when (((rotation % 360) + 360) % 360) {
            90 -> RectF(ft * w, (1f - fr) * h, fb * w, (1f - fl) * h)
            180 -> RectF((1f - fr) * w, (1f - fb) * h, (1f - fl) * w, (1f - ft) * h)
            270 -> RectF((1f - fb) * w, fl * h, (1f - ft) * w, fr * h)
            else -> RectF(fl * w, ft * h, fr * w, fb * h)
        }
        val regio = Rect(
            crop.left + region.left.roundToInt(),
            crop.top + region.top.roundToInt(),
            crop.left + region.right.roundToInt(),
            crop.top + region.bottom.roundToInt()
        ).apply { intersect(0, 0, imgW, imgH) }

        // Hoogte van de kaart in het geroteerde beeld bepaalt de sample-size.
        val draait = rotation % 180 != 0
        val kaartHoogte = if (draait) regio.width() else regio.height()
        var sample = 1
        while (kaartHoogte / (sample * 2) >= MAX_KAART_HOOGTE) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        @Suppress("DEPRECATION")
        val decoder = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false)
            ?: throw IllegalStateException("JPEG niet leesbaar")
        val stuk = try {
            decoder.decodeRegion(regio, opts) ?: throw IllegalStateException("Regio niet leesbaar")
        } finally {
            decoder.recycle()
        }

        val gedraaid = if (rotation % 360 != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val r = Bitmap.createBitmap(stuk, 0, 0, stuk.width, stuk.height, m, true)
            if (r !== stuk) stuk.recycle()
            r
        } else stuk

        return schaalNaarHoogte(gedraaid, MAX_KAART_HOOGTE)
    }

    /** Schaalt alleen omlaag; geeft hetzelfde bitmap terug als het al past. */
    fun schaalNaarHoogte(bmp: Bitmap, hoogte: Int): Bitmap {
        if (bmp.height <= hoogte) return bmp
        val nw = (bmp.width.toLong() * hoogte / bmp.height).toInt().coerceAtLeast(1)
        val r = Bitmap.createScaledBitmap(bmp, nw, hoogte, true)
        if (r !== bmp) bmp.recycle()
        return r
    }

    /** Voorkant links, achterkant rechts, witte achtergrond. Recyclet de invoer. */
    fun voegSamen(voor: Bitmap, achter: Bitmap): Bitmap {
        val hoogte = min(MAX_KAART_HOOGTE, min(voor.height, achter.height))
        val v = schaalNaarHoogte(voor, hoogte)
        val a = schaalNaarHoogte(achter, hoogte)
        val breedte = v.width + TUSSENRUIMTE + a.width
        val uit = Bitmap.createBitmap(breedte, hoogte, Bitmap.Config.ARGB_8888)
        val c = Canvas(uit)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(v, 0f, ((hoogte - v.height) / 2).toFloat(), p)
        c.drawBitmap(a, (v.width + TUSSENRUIMTE).toFloat(), ((hoogte - a.height) / 2).toFloat(), p)
        v.recycle()
        a.recycle()
        return uit
    }

    /** Kleine kopie voor weergave op het scherm (maximale breedte in px). */
    fun voorbeeld(bmp: Bitmap, maxBreedte: Int): Bitmap {
        if (bmp.width <= maxBreedte) return bmp.copy(Bitmap.Config.ARGB_8888, false)
        val nh = max(1, (bmp.height.toLong() * maxBreedte / bmp.width).toInt())
        return Bitmap.createScaledBitmap(bmp, maxBreedte, nh, true)
    }
}
