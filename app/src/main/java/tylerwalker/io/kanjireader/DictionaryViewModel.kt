package tylerwalker.io.kanjireader

import android.app.Application
import android.arch.lifecycle.AndroidViewModel
import android.arch.lifecycle.MediatorLiveData
import android.arch.lifecycle.MutableLiveData
import android.view.View

class DictionaryViewModel(val app: Application): AndroidViewModel(app) {
    val character = MutableLiveData<String>()
    val kunReading = MutableLiveData<String>()
    val onReading = MutableLiveData<String>()
    val meaning = MutableLiveData<String>()

    fun goToDictionary() { navigationProcessor.onNext(NavigationEvent.Dictionary) }
    fun goToMain() { navigationProcessor.onNext(NavigationEvent.Main) }
}