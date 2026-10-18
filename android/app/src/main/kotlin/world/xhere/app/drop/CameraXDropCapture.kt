package world.xhere.app.drop

import android.content.Context
import android.content.ContextWrapper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * One still from the back camera. The composer does not keep a preview running:
 * the shutter binds CameraX, writes a JPEG, and unbinds. Exif is stripped later
 * by [world.xhere.shared.data.MediaRepository], not here.
 */
class CameraXDropCapture : DropCapture {
    override val needsPermission: Boolean = true

    override suspend fun takePhoto(context: Context): CapturedPhoto = withContext(Dispatchers.Main) {
        val owner = context.findLifecycleOwner()
            ?: throw IllegalStateException("camera needs an activity")
        val provider = ProcessCameraProvider.getInstance(context).await(context)
        val capture = ImageCapture.Builder().build()
        provider.unbindAll()
        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, capture)
        try {
            suspendCancellableCoroutine { continuation ->
                val file = File.createTempFile("xhere-", ".jpg", context.cacheDir)
                val options = ImageCapture.OutputFileOptions.Builder(file).build()
                capture.takePicture(
                    options,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            val bytes = file.readBytes()
                            file.delete()
                            if (continuation.isActive) continuation.resume(CapturedPhoto(bytes, "image/jpeg"))
                        }

                        override fun onError(exception: ImageCaptureException) {
                            file.delete()
                            if (continuation.isActive) continuation.resumeWithException(exception)
                        }
                    },
                )
            }
        } finally {
            provider.unbindAll()
        }
    }
}

private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(context: Context): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                if (!continuation.isActive) return@addListener
                try {
                    continuation.resume(get())
                } catch (error: Throwable) {
                    continuation.resumeWithException(error)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is LifecycleOwner) return current
        current = current.baseContext
    }
    return null
}
