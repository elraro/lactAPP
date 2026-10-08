package eu.elraro.babytracker

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import eu.elraro.babytracker.ui.AppRoot
import eu.elraro.babytracker.ui.theme.BabyTrackerTheme

// AppCompatActivity (not ComponentActivity) is what makes the per-app language work
// on Android 12 and older.
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BabyTrackerTheme {
                AppRoot()
            }
        }
    }
}
