package tylerwalker.io.kanjireader

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.content.Intent
import android.os.Handler


@ExperimentalUnsignedTypes
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        val intent = Intent(this, MainActivity::class.java)

        Handler().postDelayed({
            startActivity(intent)
            finish()
            overridePendingTransition(-1, R.anim.bottom_down)
        }, 2000L)
    }
}
