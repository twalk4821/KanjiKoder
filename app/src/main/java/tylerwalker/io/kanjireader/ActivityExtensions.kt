package tylerwalker.io.kanjireader

import android.app.Activity
import android.util.Log
import android.widget.Toast
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

fun Activity.toast(text: String, duration: Int = Toast.LENGTH_LONG) =
    Toast.makeText(this, text, duration).show()

fun Activity.log(text: String, errorSeverity: Boolean = false) {
    if (errorSeverity) {
        Log.e(this::class.java.simpleName, text)
    } else {
        Log.d(this::class.java.simpleName, text)
    }
}

/** Memory-map the model file in Assets. */
fun Activity.
        loadModelFile(path: String): MappedByteBuffer {
    val fileDescriptor = assets.openFd(path)    

    val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
    val fileChannel = inputStream.channel

    val startOffset = fileDescriptor.startOffset
    val declaredLength = fileDescriptor.declaredLength
    return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
}
