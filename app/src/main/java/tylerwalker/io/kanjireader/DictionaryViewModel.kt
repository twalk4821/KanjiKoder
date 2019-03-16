package tylerwalker.io.kanjireader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import android.view.View

class DictionaryViewModel(val app: Application): AndroidViewModel(app) {
    val character = MutableLiveData<String>()
    val kunReading = MutableLiveData<String>()
    val onReading = MutableLiveData<String>()
    val meaning = MutableLiveData<String>()
    val onReadingRomaji = MutableLiveData<String>()
    val kunReadingRomaji = MutableLiveData<String>()

    val onReadingText = MediatorLiveData<String>().apply {
        addSource(onReading) {
            if (it == "null") {
                postValue("")
            } else {
                postValue("(${it.trim()})")
            }
        }
    }

    val kunReadingText = MediatorLiveData<String>().apply {
        addSource(kunReading) {
            if (it == "null") {
                postValue("")
            } else {
                postValue("(${it.trim()})")
            }
        }
    }

    val onReadingRomajiText = MediatorLiveData<String>().apply {
        addSource(onReadingRomaji) {
            if (it == "null") {
                postValue("")
            } else {
                postValue("(${it.trim()})")
            }
        }
    }

    val kunReadingRomajiText = MediatorLiveData<String>().apply {
        addSource(kunReadingRomaji) {
            if (it == "null") {
                postValue("")
            } else {
                postValue("(${it.trim()})")
            }
        }
    }

    fun goToMain() { navigationProcessor.onNext(NavigationEvent.Main) }
}