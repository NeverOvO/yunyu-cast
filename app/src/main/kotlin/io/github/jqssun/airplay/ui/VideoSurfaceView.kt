package io.github.jqssun.airplay.ui

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

// shared surface for mirroring (VideoRenderer) and airplay video (AirPlayVideoPlayer)
@Composable
fun VideoSurfaceView(
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    aspectRatio: Float = 16f / 9f,
    // false = caller sizes the surface (content-scale modes)
    applyAspectRatio: Boolean = true,
    modifier: Modifier = Modifier
) {
    val available = rememberUpdatedState(onSurfaceAvailable)
    val destroyed = rememberUpdatedState(onSurfaceDestroyed)
    val safeAspect = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
    val callbacks = remember {
        object : SurfaceHolder.Callback {
            var attached: Surface? = null
            override fun surfaceCreated(holder: SurfaceHolder) {
                attached = holder.surface
                available.value(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {
                attached = holder.surface
                available.value(holder.surface)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                detach()
            }
            fun detach() {
                val surface = attached ?: return
                attached = null
                destroyed.value(surface)
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).also {
                it.holder.addCallback(callbacks)
            }
        },
        modifier = if (applyAspectRatio) {
            modifier
                .aspectRatio(safeAspect, matchHeightConstraintsFirst = safeAspect < 1f)
                .fillMaxSize()
        } else {
            modifier
        },
        onReset = null,
        onRelease = { view ->
            view.holder.removeCallback(callbacks)
            callbacks.detach()
        }
    )
}
