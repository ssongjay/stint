package dev.stint.app

import android.app.Application
import android.os.Bundle
import android.content.pm.ApplicationInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.AndroidViewModel
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import dev.stint.app.client.StintController
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
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val viewModel: StintViewModel by viewModels()
    private val controller get() = viewModel.controller
    private val notification by lazy { TimerNotification(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notification.requestPermissionIfNeeded()
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
            TimerNotificationEffect(controller, notification)
            StintApp(controller, foldPosture)
        }
    }
}

class StintViewModel(application: Application) : AndroidViewModel(application) {
    val controller =
        StintController(
            client = androidHttpClient(),
            localStore = AndroidLocalStore(application.applicationContext),
            clock = System::currentTimeMillis,
            newId = { UUID.randomUUID().toString() },
            allowInsecureLoopback = application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )

    override fun onCleared() {
        controller.close()
        super.onCleared()
    }
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

private fun androidHttpClient(): HttpClient = HttpClient(OkHttp) {
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
