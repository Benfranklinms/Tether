package dev.benfranklin.tether.v6

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import dev.benfranklin.tether.runtime

/**
 * Share › "Add to Shelf" (0.5.2): shared text, links and files become Shelf cards on the phone
 * and, once linked, on the Mac. Works offline; the Shelf syncs on the next connection.
 */
class ShelfShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v6 = runtime.v6
        val uris = when (intent.action) {
            android.content.Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_STREAM, Uri::class.java))
            android.content.Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, android.content.Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        val text = intent.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)?.toString()
        val subject = intent.getStringExtra(android.content.Intent.EXTRA_SUBJECT)
        when {
            uris.isNotEmpty() -> {
                uris.forEach(v6::addFile)
                toast(if (uris.size == 1) "Added to Shelf" else "Added ${uris.size} items to Shelf")
            }
            !text.isNullOrBlank() -> {
                // A page title + link pair from a browser share becomes a titled link card.
                val body = if (!subject.isNullOrBlank() && !text.contains(subject)) "$subject\n$text" else text
                v6.addText(body)
                toast("Added to Shelf")
            }
            else -> toast("Nothing to add")
        }
        finish()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
