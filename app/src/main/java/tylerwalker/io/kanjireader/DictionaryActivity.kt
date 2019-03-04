package tylerwalker.io.kanjireader

import android.arch.lifecycle.ViewModelProviders
import android.content.Intent
import android.databinding.DataBindingUtil
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import io.reactivex.disposables.CompositeDisposable
import tylerwalker.io.kanjireader.databinding.DictionaryActivityBinding

class DictionaryActivity : AppCompatActivity() {
    companion object {
        const val KANJI_KEY = "kanji"
        const val ON_KEY = "on"
        const val KUN_KEY = "kun"
        const val MEANING_KEY = "meaning"
    }

    lateinit var viewModel: DictionaryViewModel
    lateinit var binding: DictionaryActivityBinding

    private var compositeDisposable = CompositeDisposable()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val kanji = intent.getStringExtra(KANJI_KEY)
        val onReading = intent.getStringExtra(ON_KEY)
        val kunReading = intent.getStringExtra(KUN_KEY)
        val meaning = intent.getStringExtra(MEANING_KEY)

        viewModel = ViewModelProviders.of(this).get(DictionaryViewModel::class.java).apply {
            character.value = kanji
            this.onReading.value = onReading
            this.kunReading.value = kunReading
            this.meaning.value = meaning
        }

        binding = DataBindingUtil.setContentView<DictionaryActivityBinding>(this, R.layout.activity_dictionary).apply {
            setLifecycleOwner(this@DictionaryActivity)
            viewModel = this@DictionaryActivity.viewModel
        }
    }


    @ExperimentalUnsignedTypes
    override fun onStart() {
        super.onStart()

        compositeDisposable.add(navigationFlowable.subscribe {
            when (it) {
                NavigationEvent.Main -> startActivity(Intent(this, MainActivity::class.java))
            }
        })
    }

    override fun onStop() {
        super.onStop()

        compositeDisposable.clear()
        compositeDisposable = CompositeDisposable()
    }
}
