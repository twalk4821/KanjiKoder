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

    fun goToDictionary() { navigationProcessor.onNext(NavigationEvent.Dictionary) }
    fun goToMain() { navigationProcessor.onNext(NavigationEvent.Main) }
}