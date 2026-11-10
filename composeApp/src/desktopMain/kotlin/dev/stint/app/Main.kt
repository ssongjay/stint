package dev.stint.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.runBlocking
import java.awt.Dimension
import java.awt.Point
import java.util.UUID

fun main() {
    // Let macOS tint the monochrome status item for either menu-bar appearance.
    System.setProperty("apple.awt.enableTemplateImages", "true")
    stintApplication()
}
private fun stintApplication() = application {
    // 창과 메뉴 막대가 같은 컨트롤러를 봐야 명령 순서와 WebSocket 연결도 하나로 유지된다.
    // remember는 Compose 재구성 때 이 장수명 객체가 중복 생성되는 것도 막는다.
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
    val completionNotifications = remember { DesktopCompletionNotifications() }
    var windowVisible by remember { mutableStateOf(true) }
    var timerPopoverAnchor by remember { mutableStateOf<Point?>(null) }
    var selectedSubjectId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectSubject: (String) -> Unit = { id ->
        if (controller.state.value.snapshot?.timer?.status == TimerStatus.IDLE) selectedSubjectId = id
    }
    val windowState = rememberWindowState(size = DpSize(1100.dp, 780.dp))
    val menuBarAvailable = DesktopMenuBar(
        controller = controller,
        onOpen = { windowVisible = true },
        onQuit = {
            runBlocking { completionNotifications.close() }
            exitApplication()
        },
        onShowTimer = { anchor ->
            timerPopoverAnchor = if (timerPopoverAnchor == null) anchor else null
        },
    )
    LaunchedEffect(controller, completionNotifications) {
        completionNotifications.authorize()
        controller.state.collect(completionNotifications::update)
    }
    DisposableEffect(controller, completionNotifications) {
        val shutdown = Thread({
            runBlocking { completionNotifications.close() }
        }, "stint-notification-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdown)
        onDispose {
            runBlocking { completionNotifications.close() }
            controller.close()
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
        }
    }
    timerPopoverAnchor?.let { anchor ->
        DesktopTimerPopover(
            controller = controller,
            anchor = anchor,
            onDismiss = { timerPopoverAnchor = null },
            onOpen = { windowVisible = true },
            onQuit = {
                runBlocking { completionNotifications.close() }
                exitApplication()
            },
            selectedSubjectId = selectedSubjectId,
            onSelectSubject = selectSubject,
        )
    }

    Window(
        visible = windowVisible,
        onCloseRequest = {
            // 메뉴 막대가 있으면 창 닫기는 앱 종료가 아니다. 타이머와 종료 알림은 계속 살아 있어야 한다.
            if (menuBarAvailable) windowVisible = false else exitApplication()
        },
        title = "Stint",
        state = windowState,
    ) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(760, 620) }
        StintApp(
            controller = controller,
            onTestNotification = completionNotifications::testNotification,
            selectedSubjectId = selectedSubjectId,
            onSelectSubject = selectSubject,
        )
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
