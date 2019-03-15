package tylerwalker.io.kanjireader

import androidx.lifecycle.ViewModelProviders
import android.content.Intent
import androidx.databinding.DataBindingUtil
import androidx.appcompat.app.AppCompatActivity
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
        const val ON_ROMAJI_KEY = "on_romaji"
        const val KUN_ROMAJI_KEY = "kun_romaji"
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
        val onRomaji = intent.getStringExtra(ON_ROMAJI_KEY)
        val kumRomaji = intent.getStringExtra(KUN_ROMAJI_KEY)

        viewModel = ViewModelProviders.of(this).get(DictionaryViewModel::class.java).apply {
            character.value = kanji
            this.onReading.value = onReading
            this.kunReading.value = kunReading
            this.meaning.value = meaning
            this.onReadingRomaji.value = onRomaji
            this.kunReadingRomaji.value = kumRomaji
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
