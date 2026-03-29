package sh.haven.core.wayland

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Composable that displays the native Wayland compositor output
 * and forwards touch input to the compositor.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun WaylandDesktopView(modifier: Modifier = Modifier) {
    DisposableEffect(Unit) {
        onDispose {
            WaylandBridge.nativeSetSurface(null)
        }
    }

    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        WaylandBridge.nativeSetSurface(holder.surface)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        WaylandBridge.nativeSetSurface(null)
                    }
                })

                setOnTouchListener { view, event ->
                    val nx = event.x / view.width
                    val ny = event.y / view.height
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN ->
                            WaylandBridge.nativeSendTouch(0, nx, ny)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            WaylandBridge.nativeSendTouch(1, nx, ny)
                        MotionEvent.ACTION_MOVE ->
                            WaylandBridge.nativeSendTouch(2, nx, ny)
                    }
                    true
                }
            }
        },
        modifier = modifier,
    )
}
