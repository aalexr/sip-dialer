package app.sipdialer.ui.call

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.sipdialer.SipDialerApp
import app.sipdialer.ui.theme.SipDialerTheme

class InCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // showWhenLocked / turnScreenOn are set in the manifest.
        handle(intent)
        setContent {
            SipDialerTheme {
                InCallScreen(onFinished = ::finishAndRemoveTask)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        intent.getStringExtra(EXTRA_ANSWER)?.let { id ->
            SipDialerApp.instance.telecom.answer(id)
            intent.removeExtra(EXTRA_ANSWER)
        }
    }

    companion object {
        private const val EXTRA_ANSWER = "answer_call_id"

        fun intent(context: Context, answerCallId: String? = null): Intent =
            Intent(context, InCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { answerCallId?.let { putExtra(EXTRA_ANSWER, it) } }
    }
}
