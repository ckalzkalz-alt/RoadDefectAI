package com.roaddefect.ai

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.roaddefect.ai.ui.AppNav
import com.roaddefect.ai.ui.RoadDefectTheme
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.roaddefect.ai.inspection.Phase

/**
 * Single Activity hosting Compose. Rotation is handled in-place (configChanges in the manifest) so the
 * camera session is not torn down; the InspectionController lives in the Application and survives anyway.
 */
class MainActivity : ComponentActivity() {

    private val container get() = (application as RoadDefectApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the screen on only while an inspection is running (mounted phone, no interaction).
        lifecycleScope.launch {
            container.controller.state.collectLatest { s ->
                if (s.isRunning) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        // Camera stops when the app is backgrounded, so pause rather than silently recording nothing.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (container.controller.state.value.phase == Phase.ACTIVE && !isChangingConfigurations) {
                    container.controller.pause(auto = true)
                }
            }
        })

        setContent { RoadDefectTheme { AppNav(container) } }
    }
}
