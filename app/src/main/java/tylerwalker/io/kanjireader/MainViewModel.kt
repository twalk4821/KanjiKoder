package tylerwalker.io.kanjireader

import android.app.Application
import android.arch.lifecycle.AndroidViewModel
import android.arch.lifecycle.MediatorLiveData
import android.arch.lifecycle.MutableLiveData
import android.view.View

class MainViewModel(val app: Application): AndroidViewModel(app) {
    val character = MutableLiveData<String>()
    val kunReading = MutableLiveData<String>()
    val onReading = MutableLiveData<String>()
    val meaning = MutableLiveData<String>()
    val likelihood = MutableLiveData<Float>()

    val predictionCharacterText = MediatorLiveData<String>().apply {
        addSource(character) {
            it?.let { predictedCharacter ->
                value = app.getString(R.string.prediction_character_template, predictedCharacter)
            }
        }
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

    val predictionLikelihoodText = MediatorLiveData<String>().apply {
        addSource(likelihood) {
            it?.let { predictionLikelihood ->
                value = app.getString(R.string.prediction_likelihood_template, predictionLikelihood.toString())
            }
        }
    }

    val predictionConfidenceText = MediatorLiveData<String>().apply {
        addSource(likelihood) {
            it?.let { predictionLikelihood ->
                value = when {
                    predictionLikelihood == 0F -> "???"
                    predictionLikelihood < .25F -> app.getString(R.string.prediction_confidence_weak)
                    predictionLikelihood < .50F -> app.getString(R.string.prediction_confidence_fair)
                    predictionLikelihood < .75F -> app.getString(R.string.prediction_confidence_strong)
                    else -> app.getString(R.string.prediction_confidence_very_strong)
                }
            }
        }
    }

    val predictionConfidenceColor = MediatorLiveData<Int>().apply {
        addSource(likelihood) {
            it?.let { predictionLikelihood ->
                value = when {
                    predictionLikelihood < .25F -> R.color.confidence_weak
                    predictionLikelihood < .50F -> R.color.confidence_fair
                    predictionLikelihood < .75F -> R.color.confidence_strong
                    else -> R.color.confidence_very_strong
                }
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

    fun getDetailsForEntry() {

    }
}