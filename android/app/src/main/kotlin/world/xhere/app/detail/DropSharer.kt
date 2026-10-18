package world.xhere.app.detail

import android.content.Context
import android.content.Intent

/** Hands a drop link to the system share sheet. */
fun interface DropSharer {
    fun share(url: String)
}

class AndroidDropSharer(private val context: Context) : DropSharer {
    override fun share(url: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
