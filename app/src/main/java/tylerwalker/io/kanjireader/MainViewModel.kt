package tylerwalker.io.kanjireader

import android.app.Application
import android.arch.lifecycle.AndroidViewModel
import android.arch.lifecycle.MediatorLiveData
import android.arch.lifecycle.MutableLiveData

class MainViewModel(val app: Application): AndroidViewModel(app) {
    val character = MutableLiveData<String>()
    val meaning = MutableLiveData<String>()
    val likelihood = MutableLiveData<Float>()

    val predictionCharacterText = MediatorLiveData<String>().apply {
        addSource(character) {
            it?.let { predictedCharacter ->
                value = app.getString(R.string.prediction_character_template, predictedCharacter)
            }
        }
    }

    val predictionMeaningText = MediatorLiveData<String>().apply {
        addSource(meaning) {
            it?.let { predictedMeaning ->
                value = app.getString(R.string.prediction_meaning_template, predictedMeaning)
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
}