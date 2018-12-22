package tylerwalker.io.kanjireader

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.ImageFormat.YUV_420_888
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
import com.google.ar.core.ArCoreApk
import com.google.ar.core.ArCoreApk.InstallStatus.*
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import kotlinx.android.synthetic.main.activity_main.*
import org.opencv.core.Mat
import org.opencv.android.LoaderCallbackInterface
import org.opencv.android.BaseLoaderCallback
import org.opencv.android.OpenCVLoader
import java.lang.IndexOutOfBoundsException

typealias PixelArray = MutableList<MutableList<Int>>

@ExperimentalUnsignedTypes
class MainActivity : AppCompatActivity() {
    var arSession: Session? = null
    private var userRequestedInstall: Boolean = true
    private lateinit var cameraManager: CameraManager
    lateinit var imageReader: ImageReader

    lateinit var previewSurface: Surface
    lateinit var recordingSurface: Surface

    var isStreamingData = false

    lateinit var previewSize: Size
    lateinit var decodeSize: Size

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        surfaceView.holder.addCallback(surfaceReadyCallback)

        startStream.setOnClickListener {
            it as Button

            if (isStreamingData) {
                isStreamingData = false
                it.text = "Start Stream"
            } else {
                isStreamingData = true
                it.text = "Stop Stream"
            }
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

                override fun onOpened(camera: CameraDevice) {
                    log("onOpened()")

                    val cameraCharacteristics = getCameraCharacteristics(camera.id)
                    val configMap = cameraCharacteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]

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
                    previewSize = yuvSizes.first()
                    decodeSize = yuvSizes.getDecodeSize()

                    log("decode size:, width: ${decodeSize.width}, height: ${decodeSize.height}")

                    surfaceView.holder.setFixedSize(previewSize.width, previewSize.height)

                    // Configure Image Reader
                    imageReader = ImageReader.newInstance(decodeSize.width, decodeSize.height, YUV_420_888, 2)
                    imageReader.setOnImageAvailableListener(decodeImageToPixels, Handler { true })

                    previewSurface = surfaceView.holder.surface
                    recordingSurface = imageReader.surface


                    camera.createCaptureSession(mutableListOf(previewSurface, recordingSurface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigureFailed(session: CameraCaptureSession) { log("onConfigureFailed()") }

                        override fun onConfigured(session: CameraCaptureSession) {
                            log("onConfigured()")

                            val captureRequest = camera.createCaptureRequest(TEMPLATE_PREVIEW).run {
                                addTarget(recordingSurface)
                                addTarget(previewSurface)
                                build()
                            }

                            session.setRepeatingRequest(captureRequest, object: CameraCaptureSession.CaptureCallback() {}, Handler { true })
                        }
                    }, Handler { true })
                }
            }, Handler { true })
        }
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
        var offset = 5
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

                    val pixelArray = rgbaMat.decodeRGBAToBinary().rotate()

                    for (row in pixelArray) {
                        log(row.joinToString(""))
                    }

                    isStreamingData = false
                    startStream.text = "Start Stream"

                    log("end decode")
                }
            } catch (e: Throwable) {
                isStreamingData = false
                startStream.text = "Start Stream"
                log(e.toString())
            } finally {
                close()
            }
        }
    }

    /**
     * Input Mat must already be RGBA
     */
    private fun Mat.decodeRGBAToBinary(): PixelArray {
        val mapped: PixelArray = mutableListOf()
        for (row in 0 until decodeSize.height) {
            mapped.add(mutableListOf())
            for (col in 0 until decodeSize.width) {
                val byte = this[row, col]
                mapped[row].add(if (byte.isBlack()) 1 else 0)
            }
        }
        return mapped
    }

    /**
     * Direction of rotation is clockwise
     */
    private fun PixelArray.rotate(): PixelArray {
        val outputMap: PixelArray = mutableListOf()
        val numRows = size
        val numCols = this[0].size

        for (col in 0 until numCols) {
            outputMap.add(mutableListOf())
        }

        for (row in numRows - 1 downTo 0) {
            for (col in numCols - 1 downTo 0) {
                outputMap[col].add(this[row][col])
            }
        }

        return outputMap
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

    /** Check if this device has a camera */
    private fun checkCameraHardware(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)

    private fun requestArCoreInstall() {

        try {
            when (ArCoreApk.getInstance().requestInstall(this, userRequestedInstall)) {
                INSTALLED -> arSession = Session(this)
                INSTALL_REQUESTED -> { userRequestedInstall = false; return }
                else -> {
                    toast("ARCore Installation is needed to run this application")
                    return
                }
            }
        } catch (e: Throwable) {
            when (e) {
                is UnavailableUserDeclinedInstallationException -> { toast("TODO: handle exception: $e"); return }
                else -> { toast("TODO: handle exception: $e"); return }
            }
        }
    }
}
