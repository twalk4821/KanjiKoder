package tylerwalker.io.kanjireader

import android.annotation.SuppressLint
import android.arch.lifecycle.ViewModelProviders
import android.content.Context
import android.databinding.DataBindingUtil
import android.graphics.Camera
import android.graphics.ImageFormat
import android.graphics.ImageFormat.YUV_420_888
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW
import android.media.ImageReader
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.Button
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_main.*
import org.opencv.core.Mat
import org.opencv.android.LoaderCallbackInterface
import org.opencv.android.BaseLoaderCallback
import org.opencv.android.OpenCVLoader
import org.tensorflow.lite.Interpreter
import java.lang.IndexOutOfBoundsException
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.ByteOrder.nativeOrder
import kotlin.math.log
import android.hardware.camera2.CaptureRequest
import android.support.v4.view.MotionEventCompat.getPointerCount
import android.hardware.camera2.CameraCharacteristics
import android.system.Os.close
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlinx.android.synthetic.main.activity_main.view.*
import tylerwalker.io.kanjireader.R.id.*
import tylerwalker.io.kanjireader.databinding.MainActivityBinding
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.roundToInt


typealias PixelArray = MutableList<Long>
typealias Prediction = Pair<Int, Float>
typealias SoftmaxArray = FloatArray

@ExperimentalUnsignedTypes
class MainActivity : AppCompatActivity() {
    private var userRequestedInstall: Boolean = true
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProviders.of(this).get(MainViewModel::class.java).apply {
            character.value = "???"
            meaning.value = "???"
            likelihood.value = 0.00F
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
                    val k = Kanji(
                            character = elements[0],
                            id = elements[1].toInt(),
                            strokeCount = elements[2].toInt(),
                            grade = elements[3],
                            radical = elements[4],
                            onReading = elements[5],
                            kunReading = elements[6],
                            nanoriReading = elements[7],
                            meaning = elements[8],
                            label = elements[9].toInt()
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
                override fun onDisconnected(p0: CameraDevice) { log("onDisconnected()") }
                override fun onError(p0: CameraDevice, p1: Int) { log("onError()") }

                override fun onOpened(cam: CameraDevice) {
                    log("onOpened()")
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

                    slidingWindow.decodeSize = PointF(decodeSize.width.toFloat(), decodeSize.height.toFloat())
                    slidingWindow.previewSize = PointF(previewSize.width.toFloat(), previewSize.height.toFloat())
                    slidingWindow.screenSize = PointF(slidingWindow.height.toFloat(), slidingWindow.width.toFloat())

                    log("decode size:, width: ${decodeSize.width}, height: ${decodeSize.height}")
                    log("preview size:, width: ${previewSize.width}, height: ${previewSize.height}")

                    val displayRotation = windowManager.defaultDisplay.rotation
                    val swappedDimensions = areDimensionsSwapped(displayRotation = displayRotation)

                    val rotatedPreviewWidth = if (swappedDimensions) previewSize.height else previewSize.width
                    val rotatedPreviewHeight = if (swappedDimensions) previewSize.width else previewSize.height

                    surfaceView.holder.setFixedSize(rotatedPreviewWidth, rotatedPreviewHeight)

                    // Configure Image Reader
                    imageReader = ImageReader.newInstance(rotatedPreviewWidth, rotatedPreviewHeight, YUV_420_888, 2)
                    imageReader.setOnImageAvailableListener(decodeImageToPixels, Handler { true })

                    previewSurface = surfaceView.holder.surface
                    recordingSurface = imageReader.surface


                    captureCallback = object : CameraCaptureSession.StateCallback() {
                        override fun onConfigureFailed(session: CameraCaptureSession) { log("onConfigureFailed()") }

                        override fun onConfigured(session: CameraCaptureSession) {
                            log("onConfigured()")

                            previewRequestBuilder = camera.createCaptureRequest(TEMPLATE_PREVIEW).apply {
                                addTarget(recordingSurface)
                                addTarget(previewSurface)
                            }

                            session.setRepeatingRequest(previewRequestBuilder.build(), object: CameraCaptureSession.CaptureCallback() {}, Handler { true })

                            captureSession = session
                        }
                    }

                    camera.createCaptureSession(mutableListOf(previewSurface, recordingSurface), captureCallback, Handler { true })
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
        override fun surfaceChanged(p0: SurfaceHolder?, p1: Int, p2: Int, p3: Int) { log("surfaceChanged()") }
        override fun surfaceDestroyed(p0: SurfaceHolder?) { log("surfaceDestroyed()") }

        override fun surfaceCreated(p0: SurfaceHolder?) {
            log("surfaceCreated()")
            startCameraSession()
        }
    }

    private fun Array<Size>.getDecodeSize(): Size {
        var offset = 10
        var decodeSize: Size? = null

        while (decodeSize == null) {
            decodeSize = try { get(lastIndex - offset) } catch (e: IndexOutOfBoundsException) { null }
            offset -= 1
        }

        return decodeSize
    }

    /**
     * Decode image to [PixelArray]
     */
    private val decodeImageToPixels = ImageReader.OnImageAvailableListener { imageReader ->

        imageReader.acquireLatestImage()?.apply {
            try {
                val rgbaMat = toMat_RGBA()

                if (isStreamingData) {
                    log("start decode: ${rgbaMat.size()}")

                    val binary = rgbaMat.decodeRGBAToBinary()
                    val rotated = binary.rotate(decodeSize.width)
                    val cropped = rotated.cropToWindow()

                    cropped.print()

                    cropped.unwindToByteBuffer()
                    val output = arrayOf(FloatArray(2679))

                    interpreter.run(imgData, output)

                    val predictions = getTop10Predictions(output = output[0], shouldPrint = true)
                    val (prediction, likelihood) = predictions[0]
                    val topPrediction = getKanji(prediction)
                    log("top prediction: ${topPrediction}")

                    viewModel.character.value = topPrediction?.character
                    viewModel.meaning.value = topPrediction?.meaning
                    viewModel.likelihood.value = likelihood

                    isStreamingData = false

                    log("end decode")
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

        log("decode rect: ${decodeRect.left}, ${decodeRect.top}, ${decodeRect.right}, ${decodeRect.bottom}")

        val result = mutableListOf<Long>()

        for (rowNumber in decodeRect.top.toInt() until decodeRect.bottom.toInt()) {
            val row = map[rowNumber]
            for (colNumber in decodeRect.left.toInt() until decodeRect.right.toInt()) {
                val pixel = row[colNumber]
                result.add(pixel)
            }
        }
        return result
    }

    private fun PixelArray.compress(): PixelArray {
        val result: PixelArray = mutableListOf()

        val currentMap = convertToMap()

        var row = 0
        while (row + 1 < currentMap.size) {
            var col = 0
            while (col + 1 < currentMap.size) {
                val topLeft = currentMap[row][col]
                val topRight = currentMap[row + 1][col]
                val bottomLeft = currentMap[row][col + 1]
                val bottomRight = currentMap[row + 1][col + 1]

                val pixel = if (topLeft + topRight + bottomLeft + bottomRight > 2) 1L else 0L

                result.add(pixel)

                col += 2
            }
            row += 2
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
                log("Prediction #${index + 1} -- label: $label, likelihood: $likelihood")
            }
        }

        return predictions
    }

    private fun getTop10Predictions(predictions: MutableList<Prediction>): MutableList<Prediction> {
        return  predictions.apply {
            sortBy {
                it.second
            }

            subList(0, 10)
        }
    }

    /**
     * Input Mat must already be RGBA
     */
    private fun Mat.decodeRGBAToBinary(): PixelArray {
        val array: PixelArray = mutableListOf()
        for (row in 0 until height()) {
            for (col in 0 until width()) {
                val byte = this[row, col]
                array.add(if (byte.isBlack()) 1L else 0L)
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

}

