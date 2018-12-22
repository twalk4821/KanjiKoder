package tylerwalker.io.kanjireader

import android.graphics.ImageFormat
import android.media.Image
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer


fun Image.toMat(): Mat {
    var buffer: ByteBuffer
    var rowStride: Int
    var pixelStride: Int
    var offset = 0

    val data = ByteArray(width * height * ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8)
    val rowData = ByteArray(planes[0].rowStride)

    for (i in planes.indices) {
        buffer = planes[i].buffer
        rowStride = planes[i].rowStride
        pixelStride = planes[i].pixelStride
        val w = if (i == 0) width else width / 2
        val h = if (i == 0) height else height / 2
        for (row in 0 until h) {
            val bytesPerPixel = ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8
            if (pixelStride == bytesPerPixel) {
                val length = w * bytesPerPixel
                buffer.get(data, offset, length)

                if (h - row != 1) {
                    buffer.position(buffer.position() + rowStride - length)
                }
                offset += length
            } else {


                if (h - row == 1) {
                    buffer.get(rowData, 0, width - pixelStride + 1)
                } else {
                    buffer.get(rowData, 0, rowStride)
                }

                for (col in 0 until w) {
                    data[offset++] = rowData[col * pixelStride]
                }
            }
        }
    }

    val mat = Mat(height + height / 2, width, CvType.CV_8UC1)
    mat.put(0, 0, data)

    return mat
}

/**
 * YUV Mat to RGBA Mat
 */
fun Image.toMat_RGBA(): Mat {
    val yuvMat = toMat()
    val bgrMat = Mat(height, width, CvType.CV_8UC4)

    Imgproc.cvtColor(yuvMat, bgrMat, Imgproc.COLOR_YUV2BGR_I420)

    return Mat().apply {
        Imgproc.cvtColor(bgrMat, this, Imgproc.COLOR_BGR2RGBA, 0)
    }
}

fun DoubleArray.isBlack(): Boolean = get(0) < 50 && get(1) < 50 && get(2) < 50