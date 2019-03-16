package tylerwalker.io.kanjireader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import android.view.View
import androidx.core.content.ContextCompat

class MainViewModel(val app: Application): AndroidViewModel(app) {
    val character = MutableLiveData<String>()
    val kunReading = MutableLiveData<String>()
    val onReading = MutableLiveData<String>()
    val meaning = MutableLiveData<String>()
    val likelihood = MutableLiveData<Float>()
    val onReadingRomaji = MutableLiveData<String>()
    val kunReadingRomaji = MutableLiveData<String>()
    val decodeMode = MutableLiveData<DecodeMode>()
    val decodeModeButtonText = MediatorLiveData<String>().apply {
        addSource(decodeMode) {
            if (it === DecodeMode.Inverted) {
                postValue("Detecting white text")
            } else {
                postValue("Detecting black text")
            }
        }
    }

    fun onCheckedChangedListener(view: View, isChecked: Boolean) {
        when (isChecked) {
            true -> decodeMode.postValue(DecodeMode.Inverted)
            else -> decodeMode.postValue(DecodeMode.Normal)
        }
    }

    fun showDecodeModeHelp(view: View) {
        uiEventProcessor.onNext(UIEvent.ShowDecodeHelp)
    }

    val predictionReadingsText = MediatorLiveData<String>().apply {
        var lastOnReading: String? = null
        var lastKunReading: String? = null

        addSource(onReading) {
            it?.let {
                if (it.isNotEmpty()) {
                    lastOnReading = it

                    val firstOnReading = it.split(",")[0]

                    lastKunReading?.let {
                        val firstKunReading = it.split(",")[0]

                        postValue("$firstOnReading, $firstKunReading")
                    } ?: run {
                        postValue(firstOnReading)
                    }
                }
            }
        }

        addSource(kunReading) {
            it?.let {
                lastKunReading = it

                if (it.isNotEmpty()) {
                    val firstKunReading = it.split(",")[0]

                    lastOnReading?.let {
                        val firstOnReading = it.split(",")[0]

                        postValue("$firstOnReading, $firstKunReading")
                    } ?: run {
                        postValue(firstKunReading)
                    }
                }
            }
        }
    }
    val predictionMeaningText = MediatorLiveData<String>().apply {
        addSource(meaning) {
            it?.let { predictedMeaning ->
                value = predictedMeaning.capitalize()
            }
        }
    }

    val moreButtonVisibility = MediatorLiveData<Int>().apply {
        addSource(character) {
            it?.let {
                postValue(View.VISIBLE)
            } ?: run {
                postValue(View.GONE)
            }
        }
    }

    fun goToDictionary() { navigationProcessor.onNext(NavigationEvent.Dictionary) }
    fun toggleInvertedMode() { decodeMode.value.let {
        when (it) {
            DecodeMode.Normal -> decodeMode.postValue(DecodeMode.Inverted)
            else -> decodeMode.postValue(DecodeMode.Normal)
        }
    }}
}