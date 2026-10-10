package dev.benfranklin.tether.share

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import dev.benfranklin.tether.runtime
import dev.benfranklin.tether.ui.MainActivity

/**
 * Share › "Continue on Mac" (0.5): a link or text becomes a card on the Mac (Open on Mac); a
 * file is sent and its card opens it on the Mac when it arrives.
 */
class ContinueShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rt = runtime
        if (rt.ui.value.peer == null) {
            Toast.makeText(this, "Pair Tether with your Mac first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            return finish()
        }
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent.getStringExtra(Intent.EXTRA_TITLE)
        val connected = rt.ui.value.link is dev.benfranklin.tether.core.LinkState.Connected
        when {
            uris.isNotEmpty() -> {
                rt.v5.continueFiles(uris)
                toast(if (connected) "Continuing on your Mac" else "Will continue when your Mac connects")
            }
            !text.isNullOrBlank() -> {
                rt.v5.shared(text, subject)
                toast(if (connected) "Sent to your Mac" else "Not connected to your Mac")
            }
            else -> toast("Nothing to continue")
        }
        finish()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
