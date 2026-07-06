package com.esp32esso.tier1.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// Renders `content` and tees the render into a GraphicsLayer so a share button
// elsewhere can grab a bitmap of exactly what the user is looking at. The layer
// is returned so the caller can pass it to [shareLayerAsImage].
@Composable
fun CaptureableBox(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
): GraphicsLayer {
    val layer = rememberGraphicsLayer()
    Box(
        modifier =
            modifier
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                },
    ) {
        // A solid background inside the recorded layer, because sharing a
        // transparent image against WhatsApp/Telegram etc. looks broken.
        Box(modifier = Modifier.background(EssoBgEspresso)) {
            content()
        }
    }
    return layer
}

// Convenience helper for callers that already have both a scope and a layer.
fun shareLayerAsImage(
    context: Context,
    scope: CoroutineScope,
    layer: GraphicsLayer,
    subject: String,
) {
    scope.launch {
        val image = layer.toImageBitmap()
        val bitmap = image.asAndroidBitmap()
        val uri = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            // Overwrite the same file each time so the cache doesn't grow
            // unbounded across shares.
            val file = File(dir, "esp32esso-share.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share $subject").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}

// Composable-friendly wrapper that stashes the local scope for the button.
@Composable
fun rememberShareAction(layer: GraphicsLayer, subject: String): () -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(layer, subject) {
        { shareLayerAsImage(context, scope, layer, subject) }
    }
}
