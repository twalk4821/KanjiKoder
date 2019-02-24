package tylerwalker.io.kanjireader

import android.databinding.BindingAdapter
import android.widget.TextView

@BindingAdapter("android:textColor")
fun setTextColor(textView: TextView, color: Int) {
    textView.setTextColor(textView.context.getColor(color))
}