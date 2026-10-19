package world.xhere.app.map

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import world.xhere.app.R
import world.xhere.app.ui.TestTags

/**
 * IIIF deep zoom for one Sanborn sheet. Tiles come from the image service
 * named on the cached row. The page is bundled; it does not load a script
 * from another site.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun IiifViewer(
    service: String,
    title: String,
    year: String,
    onDismiss: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize().testTag(TestTags.TIME_VIEWER)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(TestTags.TIME_VIEWER_CLOSE)) {
                Text(stringResource(R.string.here_close))
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = false
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                val payload = JSONObject()
                                    .put("service", service)
                                    .put("title", title)
                                    .put("year", year)
                                    .toString()
                                evaluateJavascript("startViewer($payload)", null)
                            }
                        }
                        loadUrl("file:///android_asset/iiif.html")
                    }
                },
            )
        }
    }
}
