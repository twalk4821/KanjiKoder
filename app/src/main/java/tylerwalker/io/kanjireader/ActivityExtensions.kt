package tylerwalker.io.kanjireader

import android.app.Activity
import android.util.Log
import android.widget.Toast

fun Activity.toast(text: String, duration: Int = Toast.LENGTH_LONG) =
    Toast.makeText(this, text, duration).show()

fun Activity.log(text: String, errorSeverity: Boolean = false) {
    if (errorSeverity) {
        Log.e(this::class.java.simpleName, text)
    } else {
        Log.d(this::class.java.simpleName, text)
    }
}
