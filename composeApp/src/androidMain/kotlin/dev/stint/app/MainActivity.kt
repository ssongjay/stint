package dev.stint.app

import android.app.Application
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.stint.shared.TimerStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.AndroidViewModel
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import dev.stint.app.ui.FoldPosture
import dev.stint.app.ui.StintApp
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.collect

class MainActivity : ComponentActivity() {
    private val viewModel: StintViewModel by viewModels()
    private val controller get() = viewModel.controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 401)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.state.map { it.snapshot?.timer?.status?.let { status -> status != TimerStatus.IDLE } == true }
                    .distinctUntilChanged().collect { active -> if (active) startTimerStatus() }
            }
        }
        val windowInfoTracker = WindowInfoTracker.getOrCreate(this)
        setContent {
            val foldPosture by produceState(FoldPosture(), windowInfoTracker) {
                windowInfoTracker.windowLayoutInfo(this@MainActivity).collect { layoutInfo ->
                    value = layoutInfo.displayFeatures
                        .filterIsInstance<FoldingFeature>()
                        .firstOrNull()
                        .toFoldPosture(resources.displayMetrics.density)
                }
            }
            StintApp(controller, foldPosture, onTestNotification = {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    "시스템 설정에서 Stint 알림을 허용해 주세요."
                } else {
                    val manager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    ensureCompletionChannel(manager)
                    manager.notify(1203, buildCompletionNotification(this@MainActivity,
                        dev.stint.app.client.TimerCompletionEvent(
                            key = "preview", phase = dev.stint.shared.TimerPhase.FOCUS, subjectName = "",
                            title = "알림 테스트", body = "집중과 휴식이 끝나면 이렇게 알려드려요.",
                        )))
                    "테스트 알림을 보냈어요. 무음·방해 금지 설정에 따라 소리가 나지 않을 수 있어요."
                }
            })
        }
    }
    override fun onResume() {
        super.onResume()
        startTimerStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 401 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startTimerStatus()
        }
    }

    private fun startTimerStatus() {
        if (controller.state.value.snapshot?.timer?.status?.let { it != TimerStatus.IDLE } != true) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (TimerStatusService.running) return
        try {
            startForegroundService(Intent(this, TimerStatusService::class.java))
        } catch (_: IllegalStateException) {
            // A concurrent transition to the background may revoke the foreground-start exemption.
        }
    }
}

class StintViewModel(application: Application) : AndroidViewModel(application) {
    val controller get() = (getApplication<Application>() as StintApplication).controller
}

internal data class FoldFeatureGeometry(
    val halfOpened: Boolean,
    val horizontal: Boolean,
    val separating: Boolean,
    val leftPx: Int,
    val topPx: Int,
    val widthPx: Int,
    val heightPx: Int,
)

internal fun mapFoldPosture(feature: FoldFeatureGeometry?, density: Float): FoldPosture {
    if (feature == null) return FoldPosture()
    val safeDensity = density.takeIf { it > 0f } ?: 1f
    return FoldPosture(
        isTabletop = feature.halfOpened && feature.horizontal,
        // A vertical feature exists only on the inner display. FLAT still identifies Fold's two work areas.
        isBook = !feature.horizontal,
        hingeLeftDp = feature.leftPx / safeDensity,
        hingeTopDp = feature.topPx / safeDensity,
        hingeWidthDp = feature.widthPx / safeDensity,
        hingeHeightDp = feature.heightPx / safeDensity,
    )
}

private fun FoldingFeature?.toFoldPosture(density: Float): FoldPosture {
    if (this == null) return mapFoldPosture(null, density)
    return mapFoldPosture(FoldFeatureGeometry(
        halfOpened = state == FoldingFeature.State.HALF_OPENED,
        horizontal = orientation == FoldingFeature.Orientation.HORIZONTAL,
        separating = isSeparating,
        leftPx = bounds.left,
        topPx = bounds.top,
        widthPx = bounds.width(),
        heightPx = bounds.height(),
    ), density)
}

internal fun androidHttpClient(): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; explicitNulls = false })
    }
    install(WebSockets)
    install(HttpTimeout) {
        requestTimeoutMillis = 10_000
        connectTimeoutMillis = 7_000
        socketTimeoutMillis = 20_000
    }
}
