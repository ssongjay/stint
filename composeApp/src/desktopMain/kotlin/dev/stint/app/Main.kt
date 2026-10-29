package dev.stint.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.stint.app.client.StintController
import dev.stint.app.ui.StintApp
import dev.stint.shared.TimerStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.awt.Dimension
import java.util.UUID

fun main() = application {
    val controller = remember {
        StintController(
            client = desktopHttpClient(),
            localStore = DesktopLocalStore(),
            clock = System::currentTimeMillis,
            newId = { UUID.randomUUID().toString() },
            allowInsecureLoopback = System.getenv("STINT_ALLOW_INSECURE_LOCALHOST") == "true",
        ).also { configured ->
            val url = System.getenv("STINT_SERVER_URL").orEmpty()
            val token = System.getenv("STINT_AUTH_TOKEN").orEmpty()
            if (url.isNotBlank() && token.isNotBlank()) configured.configure(url, token)
        }
    }
    val state by controller.state.collectAsState()
    var windowVisible by remember { mutableStateOf(true) }
    val windowState = rememberWindowState(size = DpSize(1100.dp, 780.dp))
    val trayIcon = remember { BitmapPainter(createTrayIcon()) }

    Tray(
        icon = trayIcon,
        tooltip = trayTooltip(state.snapshot?.timer?.status),
        onAction = { windowVisible = true },
        menu = {
            Item("Stint 열기", onClick = { windowVisible = true })
            Separator()
            when (state.snapshot?.timer?.status) {
                TimerStatus.RUNNING -> Item("일시정지", onClick = controller::pause)
                TimerStatus.PAUSED -> Item("계속하기", onClick = controller::resume)
                else -> Unit
            }
            if (state.snapshot?.timer?.status != TimerStatus.IDLE) {
                Item("현재 세션 끝내기", onClick = controller::finish)
            }
            Separator()
            Item("종료", onClick = {
                controller.close()
                exitApplication()
            })
        },
    )

    Window(
        visible = windowVisible,
        onCloseRequest = { windowVisible = false },
        title = "Stint",
        state = windowState,
    ) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(760, 620) }
        StintApp(controller)
    }
}

private fun desktopHttpClient(): HttpClient = HttpClient(CIO) {
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

private fun createTrayIcon(): ImageBitmap = ImageBitmap(20, 20).also { bitmap ->
    val canvas = Canvas(bitmap)
    val ink = Paint().apply { color = Color(0xFF24352C) }
    val paper = Paint().apply { color = Color(0xFFF5F0E4) }
    canvas.drawCircle(center = androidx.compose.ui.geometry.Offset(10f, 10f), radius = 9f, paint = ink)
    canvas.drawCircle(center = androidx.compose.ui.geometry.Offset(10f, 10f), radius = 5f, paint = paper)
}

private fun trayTooltip(status: TimerStatus?): String = when (status) {
    TimerStatus.RUNNING -> "Stint · 집중 중"
    TimerStatus.PAUSED -> "Stint · 잠시 멈춤"
    else -> "Stint"
}
