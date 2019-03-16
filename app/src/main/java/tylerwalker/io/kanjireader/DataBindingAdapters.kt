package tylerwalker.io.kanjireader

import android.content.ClipData
import android.graphics.Color
import android.widget.TextView
import androidx.databinding.BindingAdapter
import tylerwalker.io.kanjireader.R.id.textView

class DataBindingAdapters {
    @BindingAdapter("bind:color")
    fun setTextColor(textView: TextView, color: Int) {
        textView.setTextColor(color)
    }
}