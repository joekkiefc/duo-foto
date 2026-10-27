package nl.houseofsneijder.duoshot

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Duoshot: open → tik (voorkant) → tik (achterkant) → klaar.
 * Voor- en achterkant worden tot de kaart-geleider bijgesneden en naast
 * elkaar (voorkant links) als één JPEG in Pictures/Duoshot gezet.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var guideView: CardGuideView
    private lateinit var labelSide: TextView
    private lateinit var thumbFront: ImageView
    private lateinit var shutter: View
    private lateinit var busyLayer: View
    private lateinit var resultLayer: View
    private lateinit var resultImage: ImageView
    private lateinit var btnShare: Button
    private lateinit var btnNext: Button
    private lateinit var permissionLayer: View
    private lateinit var btnPermission: Button

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var cameraStarted = false
    private var alEensGeweigerd = false

    /** Eén thread: de voorkant is altijd klaar voordat de achterkant samengevoegd wordt. */
    private val werk: ExecutorService = Executors.newSingleThreadExecutor()

    private var opnameBezig = false
    private var voorkant: Bitmap? = null      // bijgesneden voorkant (max 2400 hoog)
    private var voorkantKlaar = false
    private var resultaatUri: Uri? = null
    private var resultaatVoorbeeld: Bitmap? = null

    private val vraagCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        if (ok) {
            permissionLayer.visibility = View.GONE
            startCamera()
        } else {
            permissionLayer.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        guideView = findViewById(R.id.guideView)
        labelSide = findViewById(R.id.labelSide)
        thumbFront = findViewById(R.id.thumbFront)
        shutter = findViewById(R.id.shutter)
        busyLayer = findViewById(R.id.busyLayer)
        resultLayer = findViewById(R.id.resultLayer)
        resultImage = findViewById(R.id.resultImage)
        btnShare = findViewById(R.id.btnShare)
        btnNext = findViewById(R.id.btnNext)
        permissionLayer = findViewById(R.id.permissionLayer)
        btnPermission = findViewById(R.id.btnPermission)

        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        shutter.setOnClickListener { maakFoto() }
        btnShare.setOnClickListener { deel() }
        btnNext.setOnClickListener { volgendeKaart() }
        btnPermission.setOnClickListener {
            if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) && alEensGeweigerd) {
                // Definitief geweigerd: naar de app-instellingen.
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null))
                )
            } else {
                alEensGeweigerd = true
                vraagCamera.launch(Manifest.permission.CAMERA)
            }
        }

        // Tik op de zoeker = scherpstellen op dat punt.
        guideView.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                v.performClick()
                focusOp(ev.x, ev.y)
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            permissionLayer.visibility = View.GONE
            startCamera()
        } else if (!alEensGeweigerd) {
            alEensGeweigerd = true
            vraagCamera.launch(Manifest.permission.CAMERA)
        } else {
            permissionLayer.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        werk.shutdown()
        voorkant?.recycle()
        resultaatVoorbeeld?.recycle()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (resultLayer.visibility == View.VISIBLE) volgendeKaart()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------------------------------------------------------------- camera

    private fun startCamera() {
        if (cameraStarted) return
        cameraStarted = true
        // Wachten tot de PreviewView een maat heeft: de ViewPort heeft die nodig.
        previewView.post {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    bind(future.get())
                } catch (t: Throwable) {
                    Log.e(TAG, "camera bind", t)
                    cameraStarted = false
                    toast(getString(R.string.camera_mislukt))
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun bind(provider: ProcessCameraProvider) {
        val rotation = previewView.display?.rotation ?: 0

        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(rotation)
            .build()

        // ViewPort = exact het zoekerbeeld, zodat cropRect van de opname
        // overeenkomt met wat Tommy op het scherm ziet (WYSIWYG).
        val viewPort = previewView.viewPort ?: ViewPort.Builder(
            Rational(previewView.width.coerceAtLeast(1), previewView.height.coerceAtLeast(1)),
            rotation
        ).setScaleType(ViewPort.FILL_CENTER).build()

        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(capture)
            .setViewPort(viewPort)
            .build()

        provider.unbindAll()
        camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group)
        imageCapture = capture
    }

    private fun focusOp(x: Float, y: Float) {
        val cam = camera ?: return
        val punt = previewView.meteringPointFactory.createPoint(x, y)
        val actie = FocusMeteringAction.Builder(punt).build()
        try {
            cam.cameraControl.startFocusAndMetering(actie)
        } catch (t: Throwable) {
            Log.w(TAG, "focus", t)
        }
    }

    // ---------------------------------------------------------------- opnames

    private fun maakFoto() {
        val capture = imageCapture ?: return
        if (opnameBezig) return
        opnameBezig = true
        shutter.isEnabled = false
        shutter.alpha = 0.5f

        val geleider = guideView.guideFraction()
        val isVoorkant = !voorkantKlaar

        capture.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bytes: ByteArray
                val crop: Rect
                val rot: Int
                try {
                    val buf = image.planes[0].buffer
                    buf.rewind()
                    bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                    crop = Rect(image.cropRect)
                    rot = image.imageInfo.rotationDegrees
                } finally {
                    image.close()
                }
                if (isVoorkant) naVoorkant(bytes, crop, rot, geleider)
                else naAchterkant(bytes, crop, rot, geleider)
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "takePicture", exception)
                toast(getString(R.string.foto_mislukt))
                sluiterVrij()
            }
        })
    }

    private fun sluiterVrij() {
        opnameBezig = false
        shutter.isEnabled = true
        shutter.alpha = 1f
    }

    /** Foto 1 binnen: meteen door naar de achterkant; bijsnijden op de achtergrond. */
    private fun naVoorkant(bytes: ByteArray, crop: Rect, rot: Int, geleider: RectF) {
        voorkantKlaar = true
        labelSide.setText(R.string.achterkant)
        sluiterVrij()

        werk.execute {
            try {
                val bmp = KaartBeeld.snijKaartUit(bytes, crop, rot, geleider)
                val thumb = KaartBeeld.voorbeeld(bmp, 240)
                runOnUiThread {
                    voorkant?.recycle()
                    voorkant = bmp
                    thumbFront.setImageBitmap(thumb)
                    thumbFront.visibility = View.VISIBLE
                }
            } catch (t: Throwable) {
                Log.e(TAG, "voorkant", t)
                runOnUiThread {
                    toast(getString(R.string.foto_mislukt))
                    volgendeKaart()
                }
            }
        }
    }

    /** Foto 2 binnen: samenvoegen, opslaan, resultaat tonen. */
    private fun naAchterkant(bytes: ByteArray, crop: Rect, rot: Int, geleider: RectF) {
        busyLayer.visibility = View.VISIBLE

        werk.execute {
            var uit: Bitmap? = null
            try {
                val achter = KaartBeeld.snijKaartUit(bytes, crop, rot, geleider)
                val voor = voorkant ?: throw IllegalStateException("voorkant ontbreekt")
                voorkant = null
                uit = KaartBeeld.voegSamen(voor, achter)
                val uri = bewaarInGalerij(uit)
                val voorbeeld = KaartBeeld.voorbeeld(uit, 1600)
                uit.recycle()
                uit = null
                runOnUiThread { toonResultaat(uri, voorbeeld) }
            } catch (t: Throwable) {
                Log.e(TAG, "achterkant", t)
                uit?.recycle()
                runOnUiThread {
                    toast(getString(R.string.opslaan_mislukt) + ": " + (t.message ?: t.javaClass.simpleName))
                    volgendeKaart()
                }
            }
        }
    }

    private fun bewaarInGalerij(bmp: Bitmap): Uri {
        val naam = "kaart-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, naam)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Duoshot")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert mislukt")
        try {
            resolver.openOutputStream(uri).use { out ->
                if (out == null) throw IllegalStateException("Geen outputstream")
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, KaartBeeld.JPEG_KWALITEIT, out)) {
                    throw IllegalStateException("JPEG schrijven mislukt")
                }
            }
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    // ---------------------------------------------------------------- resultaat

    private fun toonResultaat(uri: Uri, voorbeeld: Bitmap) {
        resultaatUri = uri
        resultaatVoorbeeld?.recycle()
        resultaatVoorbeeld = voorbeeld
        resultImage.setImageBitmap(voorbeeld)
        busyLayer.visibility = View.GONE
        resultLayer.visibility = View.VISIBLE
        toast(getString(R.string.opgeslagen))
    }

    private fun deel() {
        val uri = resultaatUri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.delen_via)))
    }

    private fun volgendeKaart() {
        resultLayer.visibility = View.GONE
        busyLayer.visibility = View.GONE
        resultImage.setImageDrawable(null)
        resultaatVoorbeeld?.recycle()
        resultaatVoorbeeld = null
        resultaatUri = null
        voorkant?.recycle()
        voorkant = null
        voorkantKlaar = false
        thumbFront.setImageDrawable(null)
        thumbFront.visibility = View.INVISIBLE
        labelSide.setText(R.string.voorkant)
        sluiterVrij()
    }

    private fun toast(tekst: String) {
        Toast.makeText(this, tekst, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "Duoshot"
    }
}
