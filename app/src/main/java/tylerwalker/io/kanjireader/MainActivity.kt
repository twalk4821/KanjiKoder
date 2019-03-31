package tylerwalker.io.kanjireader

import android.annotation.SuppressLint
import androidx.lifecycle.ViewModelProviders
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.databinding.DataBindingUtil
import android.graphics.ImageFormat
import android.graphics.ImageFormat.YUV_420_888
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW
import android.media.ImageReader
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_main.*
import org.opencv.core.Mat
import org.opencv.android.LoaderCallbackInterface
import org.opencv.android.BaseLoaderCallback
import org.opencv.android.OpenCVLoader
import org.tensorflow.lite.Interpreter
import java.lang.IndexOutOfBoundsException
import java.nio.ByteBuffer
import java.nio.ByteOrder.nativeOrder
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraCharacteristics
import android.preference.PreferenceManager
import android.view.MotionEvent
import android.view.View
import io.reactivex.disposables.CompositeDisposable
import it.sephiroth.android.library.xtooltip.ClosePolicy
import it.sephiroth.android.library.xtooltip.Tooltip
import tylerwalker.io.kanjireader.DictionaryActivity.Companion.KANJI_KEY
import tylerwalker.io.kanjireader.DictionaryActivity.Companion.KUN_KEY
import tylerwalker.io.kanjireader.DictionaryActivity.Companion.KUN_ROMAJI_KEY
import tylerwalker.io.kanjireader.DictionaryActivity.Companion.MEANING_KEY
import tylerwalker.io.kanjireader.DictionaryActivity.Companion.ON_KEY
import tylerwalker.io.kanjireader.DictionaryActivity.Companion.ON_ROMAJI_KEY
import tylerwalker.io.kanjireader.R.id.slidingWindow
import tylerwalker.io.kanjireader.SlidingWindow.Companion.RECT_SIZE
import tylerwalker.io.kanjireader.databinding.MainActivityBinding
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.roundToInt


typealias PixelArray = MutableList<Long>
typealias Prediction = Pair<Int, Float>
typealias SoftmaxArray = FloatArray

@ExperimentalUnsignedTypes
class MainActivity : AppCompatActivity() {
    companion object {
        private const val SHARED_PREFERENCES_FTX_KEY = "ftx"
    }
    lateinit var camera: CameraDevice
    private lateinit var cameraManager: CameraManager
    lateinit var cameraCharacteristics: CameraCharacteristics
    lateinit var captureSession: CameraCaptureSession
    lateinit var previewRequestBuilder: CaptureRequest.Builder
    lateinit var captureCallback: CameraCaptureSession.StateCallback

    //Zooming
    var fingerSpacing: Float = 0F
    var zoomLevel: Float = 1F
    private var maximumZoomLevel: Float = 1F
    private var zoom: Rect = Rect()

    lateinit var imageReader: ImageReader
    lateinit var interpreter: Interpreter
    lateinit var imgData: ByteBuffer
    private var labelProbArray: LongArray? = null

    lateinit var previewSurface: Surface
    lateinit var recordingSurface: Surface

    var isStreamingData = false

    lateinit var previewSize: Size
    lateinit var decodeSize: Size

    lateinit var slidingWindow: SlidingWindow

    lateinit var kanji: Array<Kanji>

    lateinit var viewModel: MainViewModel
    lateinit var binding: MainActivityBinding

    lateinit var sharedPreferences: SharedPreferences

    private var compositeDisposable = CompositeDisposable()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProviders.of(this).get(MainViewModel::class.java).apply {
            moreButtonVisibility.value = View.GONE
            decodeMode.value = DecodeMode.Normal
        }

        binding = DataBindingUtil.setContentView<MainActivityBinding>(this, R.layout.activity_main).apply {
            setLifecycleOwner(this@MainActivity)
            viewModel = this@MainActivity.viewModel
        }

        BufferedReader(InputStreamReader(assets.open("data.txt"))).let { reader ->
            val list = mutableListOf<Kanji>()
            var keepGoing = true
            while (keepGoing) {
                val line = reader.readLine()
                if (line == null) {
                    keepGoing = false
                } else {
                    val elements = line.split(";").map { it.replace("'", "")}
                            .map { it.replace("\"", "")}

                    val k = Kanji(
                            character = elements[0],
                            id = elements[1].toInt(),
                            strokeCount = elements[2].toInt(),
                            grade = elements[3],
                            radical = elements[4],
                            onReading = elements[5].let { if (it == "null") "" else it },
                            kunReading = elements[6].let { if (it == "null") "" else it },
                            nanoriReading = elements[7],
                            meaning = elements[8],
                            label = elements[9].toInt(),
                            onRomajiReading = elements[10],
                            kunRomajiReading = elements[11]
                    )
                    list.add(k)
                }
            }
            kanji = list.toTypedArray()
        }

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        surfaceView.holder.addCallback(surfaceReadyCallback)

        camera_button.setOnClickListener {
            isStreamingData = !isStreamingData
        }

        try {
            interpreter = Interpreter(loadModelFile("model.tflite"), object: Interpreter.Options() {
                override fun setAllowFp16PrecisionForFp32(allow: Boolean): Interpreter.Options {
                    return super.setAllowFp16PrecisionForFp32(true)
                }

                override fun setUseNNAPI(useNNAPI: Boolean): Interpreter.Options {
                    return super.setUseNNAPI(true)
                }

            })
            imgData = ByteBuffer.allocateDirect(4 * 40 * 40).apply { order(nativeOrder()) }
            labelProbArray = LongArray(1)

        } catch (e: Throwable) {
            log("$e", true)
        }

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
    }

    override fun onStart() {
        super.onStart()

        compositeDisposable.add(navigationFlowable.subscribe {
            when (it) {
                NavigationEvent.Dictionary -> startActivity(Intent(this, DictionaryActivity::class.java).apply {
                    putExtra(KANJI_KEY, viewModel.character.value)
                    putExtra(ON_KEY, viewModel.onReading.value)
                    putExtra(KUN_KEY, viewModel.kunReading.value)
                    putExtra(MEANING_KEY, viewModel.meaning.value)
                    putExtra(ON_ROMAJI_KEY, viewModel.onReadingRomaji.value)
                    putExtra(KUN_ROMAJI_KEY, viewModel.kunReadingRomaji.value)
                })
            }
        })

        compositeDisposable.add(uiEventFlowable.subscribe {
            when (it) {
                UIEvent.ShowDecodeHelp -> showToggleHelpTooltip()
            }
        })
    }

    override fun onResume() {
        super.onResume()

        // ARCore requires camera permission to operate.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            CameraPermissionHelper.requestCameraPermission(this)
            return
        }

        if (!OpenCVLoader.initDebug()) {
            log("Internal OpenCV library not found. Using OpenCV Manager for initialization")
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_4_0, this, mLoaderCallback)
        } else {
            log("OpenCV library found inside package. Using it!")
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS)
        }

        slidingWindow = findViewById(R.id.slidingWindow)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        try {
            val rect = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    ?: return false
            val currentFingerSpacing: Float

            if (event.pointerCount == 2) { //Multi touch.
                currentFingerSpacing = event.getFingerSpacing()
                var delta = 0.05f //Control this value to control the zooming sensibility
                if (fingerSpacing != 0F) {
                    if (currentFingerSpacing > fingerSpacing) { //Don't over zoom-in
                        if (maximumZoomLevel - zoomLevel <= delta) {
                            delta = maximumZoomLevel - zoomLevel
                        }
                        zoomLevel += delta
                    } else if (currentFingerSpacing < fingerSpacing) { //Don't over zoom-out
                        if (zoomLevel - delta < 1f) {
                            delta = zoomLevel - 1f
                        }
                        zoomLevel -= delta
                    }
                    val ratio = 1.toFloat() / zoomLevel //This ratio is the ratio of cropped Rect to Camera's original(Maximum) Rect
                    //croppedWidth and croppedHeight are the pixels cropped away, not pixels after cropped
                    val croppedWidth = rect.width() - Math.round(rect.width() * ratio)
                    val croppedHeight = rect.height() - Math.round(rect.height() * ratio)
                    //Finally, zoom represents the zoomed visible area
                    zoom = Rect(croppedWidth / 2, croppedHeight / 2,
                            rect.width() - croppedWidth / 2, rect.height() - croppedHeight / 2)
                    previewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoom)
                }
                fingerSpacing = currentFingerSpacing
            } else { //Single touch point, needs to return true in order to detect one more touch point
                return true
            }

            captureSession.setRepeatingRequest(previewRequestBuilder.build(), object: CameraCaptureSession.CaptureCallback() {}, Handler { true })

            return true
        } catch (e: Exception) {
            //Error handling up to you
            return true
        }

    }

    private fun MotionEvent.getFingerSpacing(): Float {
        val x = getX(0) - getX(1)
        val y = getY(0) - getY(1)
        return Math.sqrt((x * x + y * y).toDouble()).toFloat()
    }

    @SuppressLint("MissingPermission")
    private fun startCameraSession() {

        with (cameraManager) {

            if (cameraIdList.isEmpty()) {
                toast("You need a camera to use this app.")
                return
            }

            val firstCamera = cameraIdList[0]

            openCamera(firstCamera, object: CameraDevice.StateCallback() {
                override fun onDisconnected(p0: CameraDevice) { }
                override fun onError(p0: CameraDevice, p1: Int) { }

                override fun onOpened(cam: CameraDevice) {
                    camera = cam
                    cameraCharacteristics = getCameraCharacteristics(camera.id)
                    val configMap = cameraCharacteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]

                    cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.let {
                        maximumZoomLevel = it * 10
                    }



                    if (configMap == null) {
                        toast("Could not configure your camera for use with this application.")
                        log("No config map for camera: ${camera.id}", true)
                        return
                    }

                    val yuvSizes = configMap.getOutputSizes(ImageFormat.YUV_420_888)

                    if (yuvSizes.isEmpty()) {
                        toast("Could not configure your camera for use with this application.")
                        log("No sizes found for format YUV", true)
                        return
                    }

                    /* Beautiful preview */
                    previewSize = yuvSizes.last()
                    decodeSize = yuvSizes.last()

                    val displayRotation = windowManager.defaultDisplay.rotation
                    val swappedDimensions = areDimensionsSwapped(displayRotation = displayRotation)

                    val rotatedPreviewWidth = if (swappedDimensions) previewSize.height else previewSize.width
                    val rotatedPreviewHeight = if (swappedDimensions) previewSize.width else previewSize.height

                    slidingWindow.decodeSize = PointF(rotatedPreviewWidth.toFloat(), rotatedPreviewHeight.toFloat())
                    slidingWindow.previewSize = PointF(rotatedPreviewWidth.toFloat(), rotatedPreviewHeight.toFloat())
                    slidingWindow.screenSize = PointF(slidingWindow.width.toFloat(), slidingWindow.height.toFloat())
                    slidingWindow.initRectPosition()

                    surfaceView.holder.setFixedSize(rotatedPreviewWidth, rotatedPreviewHeight)

                    // Configure Image Reader
                    imageReader = ImageReader.newInstance(rotatedPreviewWidth, rotatedPreviewHeight, YUV_420_888, 2)
                    imageReader.setOnImageAvailableListener(decodeImageToPixels, Handler { true })

                    previewSurface = surfaceView.holder.surface
                    recordingSurface = imageReader.surface


                    captureCallback = object : CameraCaptureSession.StateCallback() {
                        override fun onConfigureFailed(session: CameraCaptureSession) { }

                        override fun onConfigured(session: CameraCaptureSession) {

                            previewRequestBuilder = camera.createCaptureRequest(TEMPLATE_PREVIEW).apply {
                                addTarget(recordingSurface)
                                addTarget(previewSurface)
                            }

                            session.setRepeatingRequest(previewRequestBuilder.build(), object: CameraCaptureSession.CaptureCallback() {}, Handler { true })

                            captureSession = session
                        }
                    }

                    camera.createCaptureSession(mutableListOf(previewSurface, recordingSurface), captureCallback, Handler { true })

                    val hasSeenFTX = sharedPreferences.getBoolean(SHARED_PREFERENCES_FTX_KEY, false)
                    if (!hasSeenFTX) {
                        showFirstTooltip()
                    }
                }
            }, Handler { true })
        }
    }

    private fun areDimensionsSwapped(displayRotation: Int): Boolean {
        var swappedDimensions = false
        when (displayRotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                if (cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) == 90 || cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) == 270) {
                    swappedDimensions = true
                }
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                if (cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) == 0 || cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) == 180) {
                    swappedDimensions = true
                }
            }
            else -> {
                log("Display rotation is invalid: $displayRotation")
            }
        }
        return swappedDimensions
    }

    /**
     * Start the camera session
     */
    private val surfaceReadyCallback = object: SurfaceHolder.Callback {
        override fun surfaceChanged(p0: SurfaceHolder?, p1: Int, p2: Int, p3: Int) { }
        override fun surfaceDestroyed(p0: SurfaceHolder?) { }

        override fun surfaceCreated(p0: SurfaceHolder?) {
            startCameraSession()
        }
    }

    private fun showFirstTooltip() {
        val drawnSize = slidingWindow.getDrawnSize()

        val tooltip = Tooltip.Builder(this)
                .anchor(constraintLayout.width / 2, constraintLayout.height / 2)
                .text("Hold down and drag anywhere to adjust the frame.")
                .maxWidth(constraintLayout.width / 2)
                .floatingAnimation(Tooltip.Animation.DEFAULT)
                .styleId(R.style.KanjiTooltip)
                .showDuration(10000L)
                .closePolicy(ClosePolicy.TOUCH_ANYWHERE_CONSUME)
                .create()

        tooltip.offsetY = drawnSize.y / 2F

        tooltip.doOnHidden {
            Handler().postDelayed({
                showSecondTooltip()
            }, 3000)
        }.show(slidingWindow, Tooltip.Gravity.TOP, false)

    }

    private fun showSecondTooltip() {
        val drawnSize = slidingWindow.getDrawnSize()

        val tooltip = Tooltip.Builder(this)
                .anchor(constraintLayout.width / 4, (constraintLayout.height * .75).roundToInt())
                .text("Use two fingers to zoom. Try to get one Kanji within the frame at a time.")
                .maxWidth(constraintLayout.width / 2)
                .floatingAnimation(Tooltip.Animation.DEFAULT)
                .styleId(R.style.KanjiTooltip)
                .showDuration(10000L)
                .closePolicy(ClosePolicy.TOUCH_ANYWHERE_CONSUME)
                .create()

        tooltip.offsetY = drawnSize.y / 2F

        tooltip.doOnHidden {
            Handler().postDelayed({
                showThirdTooltip()
            }, 3000)
        }.show(slidingWindow, Tooltip.Gravity.TOP, false)
    }

    private fun showThirdTooltip() {
        val drawnSize = slidingWindow.getDrawnSize()

        val tooltip = Tooltip.Builder(this)
                .anchor(constraintLayout.width / 2, constraintLayout.height - 100)
                .text("When you are ready, touch here to analyze.")
                .maxWidth(constraintLayout.width / 2)
                .floatingAnimation(Tooltip.Animation.DEFAULT)
                .styleId(R.style.KanjiTooltip)
                .showDuration(10000L)
                .closePolicy(ClosePolicy.TOUCH_ANYWHERE_CONSUME)
                .create()

        tooltip.offsetY = drawnSize.y / 2F

        tooltip.doOnHidden {
            sharedPreferences
                    .edit()
                    .putBoolean(SHARED_PREFERENCES_FTX_KEY, true)
                    .apply()
        }.show(slidingWindow, Tooltip.Gravity.TOP, false)
    }

    private fun showToggleHelpTooltip() {

        Tooltip.Builder(this)
                .anchor(decode_mode_help_icon, 0, 0, true)
                .text("Toggle to switch between white and black detection modes.")
                .maxWidth(constraintLayout.width / 2)
                .floatingAnimation(Tooltip.Animation.DEFAULT)
                .styleId(R.style.KanjiTooltip)
                .showDuration(10000L)
                .closePolicy(ClosePolicy.TOUCH_ANYWHERE_CONSUME)
                .create()
                .show(decode_mode_help_icon, Tooltip.Gravity.BOTTOM, true)
    }

    /**
     * Decode image to [PixelArray]
     */
    private val decodeImageToPixels = ImageReader.OnImageAvailableListener { imageReader ->

        imageReader.acquireLatestImage()?.apply {
            try {
                val rgbaMat = toMat_RGBA()

                if (isStreamingData) {

                    val binary = rgbaMat.decodeRGBAToBinary()
                    val rotated = binary.rotate(decodeSize.width)
                    val cropped = rotated.cropToWindow()

                    cropped.print()

                    val output = arrayOf(FloatArray(2679))
                    cropped.unwindToByteBuffer()

                    interpreter.run(imgData, output)

                    val predictions = getTop10Predictions(output = output[0], shouldPrint = true)
                    val (prediction, likelihood) = predictions[0]
                    val topPrediction = getKanji(prediction)

                    viewModel.character.value = topPrediction?.character
                    viewModel.onReading.value = topPrediction?.onReading
                    viewModel.kunReading.value = topPrediction?.kunReading
                    viewModel.meaning.value = topPrediction?.meaning
                    viewModel.likelihood.value = likelihood
                    viewModel.kunReadingRomaji.value = topPrediction?.kunRomajiReading
                    viewModel.onReadingRomaji.value = topPrediction?.onRomajiReading

                    isStreamingData = false

                }
            } catch (e: Throwable) {
                isStreamingData = false
                log(e.toString())
            } finally {
                close()
            }
        }
    }

    private fun PixelArray.cropToWindow(): PixelArray {
        val map = convertToMap(decodeSize.height)
        val decodeRect = slidingWindow.getDecodeRect()

        val result = mutableListOf<Long>()

        val rectTop = decodeRect.top.toInt()
        val rectLeft = decodeRect.left.toInt()

        for (rowNumber in rectTop until rectTop + RECT_SIZE.toInt()) {
            val row = map[rowNumber]
            for (colNumber in rectLeft until rectLeft + RECT_SIZE.toInt()) {
                val pixel = row[colNumber]
                result.add(pixel)
            }
        }
        return result
    }

    private fun getKanji(label: Int): Kanji? {
        return kanji.find { it.label == label }
    }

    private fun getTop10Predictions(output: SoftmaxArray, shouldPrint: Boolean = true): List<Prediction> {
        val predictions = mutableListOf<Prediction>()

        output.forEachIndexed { index, fl ->
            val prediction = (index + 1) to fl
            val (currentLabel, currentLikelihood) = prediction

            if (predictions.size < 10) {
                predictions.add(prediction)
            } else {
                val toReplace = predictions.find {
                    val (label, likelihood) = it
                    likelihood < currentLikelihood
                }

                if (toReplace != null) {
                    predictions[predictions.indexOf(toReplace)] = prediction
                }
            }
        }

        if (shouldPrint) {
            predictions.forEachIndexed { index, prediction ->
                val (label, likelihood) = prediction
            }
        }

        return predictions
    }

    /**
     * Input Mat must already be RGBA
     */
    private fun Mat.decodeRGBAToBinary(): PixelArray {
        val array: PixelArray = mutableListOf()
        for (row in 0 until height()) {
            for (col in 0 until width()) {
                val byte = this[row, col]
                if (viewModel.decodeMode.value === DecodeMode.Inverted) {
                    array.add(if (byte.isWhite()) 1L else 0L)
                } else {
                    array.add(if (byte.isBlack()) 1L else 0L)
                }
            }
        }
        return array
    }

    private fun PixelArray.unwindToByteBuffer() {
        imgData.rewind()
        for (pixel in this) {
                imgData.putFloat(pixel.toFloat())
        }
    }

    private fun PixelArray.print(rowWidth: Int = Math.sqrt(size.toDouble()).toInt()) {
        val numRows = size / rowWidth

        for (i in 1..numRows) {
            val startIndex = (i - 1) * rowWidth
            val endIndex = ((i - 1) * rowWidth) + (rowWidth)

            val row = subList(startIndex, endIndex)
            log(row.joinToString(""))
        }
    }

    private fun PixelArray.convertToMap(rowWidth: Int = Math.sqrt(size.toDouble()).toInt()): MutableList<MutableList<Long>> {
        val map = mutableListOf<MutableList<Long>>()

        val numRows = size/rowWidth

        for (i in 1..numRows) {
            val startIndex = (i - 1) * rowWidth
            val endIndex = ((i - 1) * rowWidth) + (rowWidth)

            val row = subList(startIndex, endIndex)
            map.add(row)
        }

        return map
    }
    /**
     * Direction of rotation is clockwise
     */
    private fun PixelArray.rotate(width: Int = Math.sqrt(size.toDouble()).toInt()): PixelArray {
        val pixelMap = convertToMap(width)
        val outputMap = mutableListOf<MutableList<Long>>()

        val numRows = pixelMap.size
        val numCols = pixelMap[0].size

        for (col in 0 until numCols) {
            outputMap.add(mutableListOf())
        }

        for (row in numRows - 1 downTo 0) {
            for (col in numCols - 1 downTo 0) {
                outputMap[col].add(pixelMap[row][col])
            }
        }

        return outputMap.flatten().toMutableList()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
                    .show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }

        recreate()
    }

    private val mLoaderCallback = object : BaseLoaderCallback(this) {
        override fun onManagerConnected(status: Int) {
            when (status) {
                LoaderCallbackInterface.SUCCESS -> {
                    log("OpenCV loaded successfully")
                }
                else -> {
                    super.onManagerConnected(status)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()

        compositeDisposable.clear()
        compositeDisposable = CompositeDisposable()
    }
}

