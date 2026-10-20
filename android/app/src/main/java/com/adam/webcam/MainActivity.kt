package com.adam.webcam

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.TonemapCurve
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Étape 2 : aperçu caméra + encodage H.264 + serveur TCP (caméra arrière/avant commutable). */
class MainActivity : Activity() {
    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FPS = 30
        const val BITRATE = 6_000_000
        const val REQ_CAMERA = 1
        const val FOCUS_REGION_RATIO = 0.16f   // taille de la zone de mise au point (fraction de la hauteur visible)
        const val FOCUS_REVERT_MS = 3000L      // retour à l'autofocus continu après un tap
        const val SLIDER_STEPS = 200           // curseurs luminosité / contraste : -1..1 en 200 pas
        const val CURVE_POINTS = 64
        const val CONTRAST_STRENGTH = 0.8f
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var switchButton: Button
    private lateinit var status: TextView
    private lateinit var focusRing: FocusRingView
    private lateinit var brightnessLabel: TextView
    private lateinit var brightnessBar: SeekBar
    private lateinit var contrastLabel: TextView
    private lateinit var contrastBar: SeekBar

    // Réglages image, -1..1 (écrits côté UI, lus côté cameraHandler)
    @Volatile private var brightness = 0f
    @Volatile private var contrast = 0f
    private val cameraThread = HandlerThread("camera").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)

    @Volatile private var previewReady = false
    @Volatile private var clientConnected = false

    // Tout ce qui suit n'est touché que depuis cameraHandler
    private var facing = CameraCharacteristics.LENS_FACING_BACK
    private var opening = false
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoder: H264Encoder? = null
    private var server: StreamServer? = null
    private var characteristics: CameraCharacteristics? = null
    private var previewRequest: CaptureRequest.Builder? = null
    private val revertFocus = Runnable { resumeContinuousFocus() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = AspectSurfaceView(this)
        switchButton = Button(this).apply {
            text = "Changer de caméra"
            setOnClickListener { switchCamera() }
        }
        status = TextView(this).apply { setTextColor(Color.WHITE); setPadding(24, 24, 24, 24) }
        focusRing = FocusRingView(this)
        val prefs = getSharedPreferences("image", MODE_PRIVATE)
        brightness = prefs.getFloat("brightness", 0f)
        contrast = prefs.getFloat("contrast", 0f)

        brightnessLabel = TextView(this).apply { setTextColor(Color.WHITE) }
        contrastLabel = TextView(this).apply { setTextColor(Color.WHITE) }
        brightnessBar = makeSlider(brightness) { v ->
            brightness = v
            prefs.edit().putFloat("brightness", v).apply()
            updateImageLabels()
            cameraHandler.post { updateImageSettings() }
        }
        contrastBar = makeSlider(contrast) { v ->
            contrast = v
            prefs.edit().putFloat("contrast", v).apply()
            updateImageLabels()
            cameraHandler.post { updateImageSettings() }
        }
        val resetButton = Button(this).apply {
            text = "Réinitialiser"
            setOnClickListener {
                brightnessBar.progress = SLIDER_STEPS / 2
                contrastBar.progress = SLIDER_STEPS / 2
            }
        }
        val dp = resources.displayMetrics.density
        val imagePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            visibility = View.GONE
            addView(brightnessLabel)
            addView(brightnessBar)
            addView(contrastLabel)
            addView(contrastBar)
            addView(resetButton)
        }
        val imageButton = Button(this).apply {
            text = "Image"
            setOnClickListener {
                imagePanel.visibility = if (imagePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        updateImageLabels()
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(focusRing, FrameLayout.LayoutParams(focusRing.size, focusRing.size, Gravity.TOP or Gravity.START))
            addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
            addView(imagePanel, FrameLayout.LayoutParams((300 * dp).toInt(), -2, Gravity.BOTTOM or Gravity.START))
            addView(LinearLayout(this@MainActivity).apply {
                addView(imageButton)
                addView(switchButton)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
        }
        setContentView(root)

        surfaceView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP && v.width > 0 && v.height > 0) {
                val nx = (event.x / v.width).coerceIn(0f, 1f)
                val ny = (event.y / v.height).coerceIn(0f, 1f)
                focusRing.show(v.left + event.x, v.top + event.y)
                cameraHandler.post { focusAt(nx, ny) }
            }
            true
        }

        surfaceView.holder.setFixedSize(WIDTH, HEIGHT)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { previewReady = true; startCamera() }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { previewReady = false; stopCamera() }
        })
    }

    override fun onStart() {
        super.onStart()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        } else {
            startCamera()
        }
    }

    override fun onStop() {
        stopCamera()
        super.onStop()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code == REQ_CAMERA && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            setStatus("Permission caméra refusée")
        }
    }

    private fun startCamera() { cameraHandler.post { openCamera() } }

    private fun stopCamera() {
        cameraHandler.post {
            closeCamera()
            server?.stop(); server = null
            encoder?.stop(); encoder = null
        }
    }

    fun switchCamera() {
        cameraHandler.post {
            closeCamera()
            facing = if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            openCamera()
        }
    }

    private fun closeCamera() {
        cameraHandler.removeCallbacks(revertFocus)
        previewRequest = null
        session?.close(); session = null
        camera?.close(); camera = null
    }

    private fun openCamera() {
        if (camera != null || opening || !previewReady) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
        }
        if (id == null) { setStatus("Caméra introuvable"); return }

        if (encoder == null) {
            val enc = H264Encoder(WIDTH, HEIGHT, FPS, BITRATE).also { it.start() }
            encoder = enc
            server = StreamServer(enc, onControl = { cmd ->
                if (cmd == Protocol.C_SWITCH_CAMERA) switchCamera()
            }, onClientChanged = { connected ->
                clientConnected = connected
                setStatus()
            }).also { it.start() }
        }

        opening = true
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                opening = false
                camera = device
                startSession(device, manager.getCameraCharacteristics(id))
            }
            override fun onDisconnected(device: CameraDevice) { opening = false; device.close(); camera = null }
            override fun onError(device: CameraDevice, error: Int) {
                opening = false; device.close(); camera = null
                setStatus("Erreur caméra $error")
            }
        }, cameraHandler)
    }

    @Suppress("DEPRECATION")
    private fun startSession(device: CameraDevice, chars: CameraCharacteristics) {
        val enc = encoder ?: return
        if (!previewReady) return
        val surfaces = listOf(surfaceView.holder.surface, enc.inputSurface)
        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                characteristics = chars
                val contrastOk = supportsContrast(chars)
                runOnUiThread { contrastBar.isEnabled = contrastOk; updateImageLabels() }
                val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                val fpsRange = ranges?.firstOrNull { it.lower == FPS && it.upper == FPS }
                    ?: ranges?.firstOrNull { it.upper == FPS }
                val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    surfaces.forEach { addTarget(it) }
                    fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    if (afModes?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true) {
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    }
                }
                previewRequest = req
                applyImageSettings(req)
                s.setRepeatingRequest(req.build(), null, cameraHandler)
                enc.requestKeyframe()
                setStatus()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { setStatus("Échec de configuration de la session") }
        }, cameraHandler)
    }

    private fun makeSlider(initial: Float, onChange: (Float) -> Unit) = SeekBar(this).apply {
        max = SLIDER_STEPS
        progress = ((initial + 1f) / 2f * SLIDER_STEPS).roundToInt()
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                onChange(value * 2f / SLIDER_STEPS - 1f)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
    }

    private fun updateImageLabels() {
        brightnessLabel.text = "Luminosité : %+.2f".format(brightness)
        contrastLabel.text = if (contrastBar.isEnabled) "Contraste : %+.2f".format(contrast) else "Contraste : non supporté par cette caméra"
    }

    private fun supportsContrast(c: CameraCharacteristics): Boolean {
        val modes = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
        val points = c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0
        return modes?.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) == true && points >= CURVE_POINTS
    }

    /** Courbe sRGB + contraste en S (smoothstep) dosé par c dans -1..1. */
    private fun contrastCurve(c: Float): TonemapCurve {
        val k = c * CONTRAST_STRENGTH
        val pts = FloatArray(CURVE_POINTS * 2)
        for (i in 0 until CURVE_POINTS) {
            val x = i / (CURVE_POINTS - 1f)
            val g = if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f
            val smooth = g * g * (3f - 2f * g)
            pts[2 * i] = x
            pts[2 * i + 1] = (g + k * (smooth - g)).coerceIn(0f, 1f)
        }
        return TonemapCurve(pts, pts, pts)
    }

    /** Applique luminosité (compensation d'exposition) et contraste (courbe de tons) au builder. */
    private fun applyImageSettings(req: CaptureRequest.Builder) {
        val c = characteristics ?: return
        val range = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        if (range != null) {
            val b = brightness
            val steps = if (b >= 0f) b * range.upper else b * -range.lower
            req.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps.roundToInt().coerceIn(range.lower, range.upper))
        }
        if (contrast != 0f && supportsContrast(c)) {
            req.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            req.set(CaptureRequest.TONEMAP_CURVE, contrastCurve(contrast))
        } else {
            req.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST)
        }
    }

    private fun updateImageSettings() {
        val s = session ?: return
        val req = previewRequest ?: return
        try {
            applyImageSettings(req)
            s.setRepeatingRequest(req.build(), null, cameraHandler)
        } catch (_: CameraAccessException) {
        } catch (_: IllegalStateException) {
        }
    }

    /** Tap-to-focus : (nx, ny) = position du toucher normalisée (0..1) dans l'aperçu. Appelé depuis cameraHandler. */
    private fun focusAt(nx: Float, ny: Float) {
        val s = session ?: return
        val c = characteristics ?: return
        val req = previewRequest ?: return
        val maxAf = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        if (maxAf <= 0) return
        val maxAe = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        // L'aperçu 16:9 est un recadrage centré du capteur (pas de rotation : app en paysage fixe).
        var cropW = active.width()
        var cropH = cropW * 9 / 16
        if (cropH > active.height()) { cropH = active.height(); cropW = cropH * 16 / 9 }
        val cx = active.left + (active.width() - cropW) / 2 + (nx * cropW).toInt()
        val cy = active.top + (active.height() - cropH) / 2 + (ny * cropH).toInt()
        val half = (min(cropW, cropH) * FOCUS_REGION_RATIO / 2).toInt()
        val rect = Rect(
            (cx - half).coerceAtLeast(active.left), (cy - half).coerceAtLeast(active.top),
            (cx + half).coerceAtMost(active.right - 1), (cy + half).coerceAtMost(active.bottom - 1),
        )
        val region = arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1))

        try {
            cameraHandler.removeCallbacks(revertFocus)
            req.set(CaptureRequest.CONTROL_AF_REGIONS, region)
            if (maxAe > 0) req.set(CaptureRequest.CONTROL_AE_REGIONS, region)
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            req.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(req.build(), null, cameraHandler)
            req.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            s.capture(req.build(), null, cameraHandler)
            req.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            s.setRepeatingRequest(req.build(), null, cameraHandler)
            cameraHandler.postDelayed(revertFocus, FOCUS_REVERT_MS)
        } catch (_: CameraAccessException) {
        } catch (_: IllegalStateException) {
        }
    }

    /** Retour à l'autofocus continu et à la métrique par défaut. */
    private fun resumeContinuousFocus() {
        val s = session ?: return
        val req = previewRequest ?: return
        val afModes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        try {
            req.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            req.set(CaptureRequest.CONTROL_AE_REGIONS, null)
            req.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(req.build(), null, cameraHandler)
            req.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            if (afModes?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true) {
                req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            s.setRepeatingRequest(req.build(), null, cameraHandler)
        } catch (_: CameraAccessException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun setStatus(text: String? = null) {
        runOnUiThread {
            status.text = text ?: buildString {
                append(if (facing == CameraCharacteristics.LENS_FACING_BACK) "Caméra arrière" else "Caméra avant")
                append(" · ${WIDTH}x$HEIGHT @ $FPS\n")
                append(if (clientConnected) "PC connecté" else "En attente du PC (port ${Protocol.PORT})")
            }
        }
    }

    override fun onDestroy() {
        cameraThread.quitSafely()
        super.onDestroy()
    }
}

/** SurfaceView qui garde le ratio 16:9 au lieu de s'étirer sur tout l'écran. */
private class AspectSurfaceView(context: Context) : SurfaceView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        var w = maxW
        var h = maxW * 9 / 16
        if (h > maxH) { h = maxH; w = maxH * 16 / 9 }
        setMeasuredDimension(w, h)
    }
}

/** Cercle affiché à l'endroit touché, qui rétrécit puis s'efface. */
private class FocusRingView(context: Context) : View(context) {
    val size = (80 * context.resources.displayMetrics.density).toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3 * context.resources.displayMetrics.density
    }

    init { alpha = 0f; isClickable = false }

    fun show(x: Float, y: Float) {
        animate().cancel()
        translationX = x - size / 2f
        translationY = y - size / 2f
        scaleX = 1.4f; scaleY = 1.4f; alpha = 1f
        animate().scaleX(1f).scaleY(1f).setDuration(200).withEndAction {
            animate().alpha(0f).setStartDelay(800).setDuration(300).start()
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        val r = size / 2f - paint.strokeWidth
        canvas.drawCircle(size / 2f, size / 2f, r, paint)
    }
}
