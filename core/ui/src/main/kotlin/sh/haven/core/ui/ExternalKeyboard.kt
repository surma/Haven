package sh.haven.core.ui

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Track whether a real external keyboard is currently connected.
 */
@Composable
fun rememberHasExternalKeyboard(): Boolean {
    val context = LocalContext.current
    val inputManager = remember(context) {
        context.getSystemService(Context.INPUT_SERVICE) as InputManager
    }
    var hasExternalKeyboard by remember(inputManager) {
        mutableStateOf(inputManager.hasExternalKeyboard())
    }

    DisposableEffect(inputManager) {
        val handler = Handler(Looper.getMainLooper())
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                hasExternalKeyboard = inputManager.hasExternalKeyboard()
            }

            override fun onInputDeviceRemoved(deviceId: Int) {
                hasExternalKeyboard = inputManager.hasExternalKeyboard()
            }

            override fun onInputDeviceChanged(deviceId: Int) {
                hasExternalKeyboard = inputManager.hasExternalKeyboard()
            }
        }
        inputManager.registerInputDeviceListener(listener, handler)
        hasExternalKeyboard = inputManager.hasExternalKeyboard()
        onDispose {
            inputManager.unregisterInputDeviceListener(listener)
        }
    }

    return hasExternalKeyboard
}

private fun InputManager.hasExternalKeyboard(): Boolean =
    inputDeviceIds.any { deviceId ->
        getInputDevice(deviceId)?.let { device ->
            !device.isVirtual &&
                device.isExternal &&
                device.supportsSource(InputDevice.SOURCE_KEYBOARD) &&
                device.keyboardType != InputDevice.KEYBOARD_TYPE_NONE
        } == true
    }
