package net.estopia.hncrevision

import android.content.Intent
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
        // Only on a fresh start, so rotating doesn't reopen the same card.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            HncTheme(dyslexia = vm.dyslexiaReading) {
                HncApp(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Widget deep link `hncrevision://card/<id>` (or [EXTRA_CARD_ID]) and the reminder's "open Due". */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val cardId = intent.getIntExtra(EXTRA_CARD_ID, -1).takeIf { it >= 0 }
            ?: intent.data?.takeIf { it.scheme == DEEP_LINK_SCHEME && it.host == "card" }?.lastPathSegment?.toIntOrNull()
        when {
            cardId != null -> vm.openCard(cardId)
            intent.action == ACTION_OPEN_DUE -> vm.openDue()
        }
    }

    companion object {
        const val DEEP_LINK_SCHEME = "hncrevision"
        const val EXTRA_CARD_ID = "net.estopia.hncrevision.extra.CARD_ID"
        const val ACTION_OPEN_DUE = "net.estopia.hncrevision.action.OPEN_DUE"
    }
}
