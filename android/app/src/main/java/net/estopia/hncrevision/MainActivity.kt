package net.estopia.hncrevision

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import net.estopia.hncrevision.ui.AppViewModel
import net.estopia.hncrevision.ui.HncApp
import net.estopia.hncrevision.ui.HncTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Check the website on launch and whenever the app returns after 30+ minutes.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.onResume()
            if (event == Lifecycle.Event.ON_STOP) vm.stopReading(pause = false)
        })
        setContent {
            HncTheme(dyslexia = vm.dyslexiaReading) {
                HncApp(vm)
            }
        }
    }
}
