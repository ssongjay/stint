package dev.stint.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.stint.shared.ClientController
import dev.stint.shared.TimerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.AWTException
import java.awt.EventQueue
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.Point
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/** Owns one native status item for as long as the application composition exists. */
@Composable
internal fun DesktopMenuBar(
    controller: ClientController,
    onOpen: () -> Unit,
    onQuit: () -> Unit,
    onShowTimer: ((Point) -> Unit)? = null,
): Boolean {
    val open by rememberUpdatedState(onOpen)
    val quit by rememberUpdatedState(onQuit)
    val showTimer by rememberUpdatedState(onShowTimer)
    var available by remember { mutableStateOf(false) }

    LaunchedEffect(controller) {
        withContext(Dispatchers.Swing) {
            if (!SystemTray.isSupported()) return@withContext
            val bar = try {
                DesktopMenuBarHost(
                    initial = menuBarTimer(controller.state.value),
                    onOpen = { open() },
                    onToggle = {
                        val latest = controller.state.value
                        if (menuBarTimer(latest).canToggle) {
                            when (latest.snapshot?.timer?.status) {
                                TimerStatus.RUNNING -> controller.pause()
                                TimerStatus.PAUSED -> controller.resume()
                                else -> Unit
                            }
                        }
                    },
                    onFinish = {
                        if (menuBarTimer(controller.state.value).canFinish) controller.finish()
                    },
                    onSkipBreak = {
                        if (menuBarTimer(controller.state.value).canSkipBreak) controller.skipBreak()
                    },
                    onQuit = { quit() },
                    onShowTimer = showTimer,
                )
            } catch (_: AWTException) {
                // Keep a reachable window when the operating system cannot install a status item.
                return@withContext
            }
            available = true
            try {
                controller.state.map(::menuBarTimer).distinctUntilChanged().collect(bar::update)
            } finally {
                available = false
                bar.close()
            }
        }
    }
    return available
}

/** All native creation, updates and disposal run on the AWT event dispatch thread. */
internal class DesktopMenuBarHost(
    initial: MenuBarTimer,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onFinish: () -> Unit,
    onSkipBreak: () -> Unit,
    onQuit: () -> Unit,
    onShowTimer: ((Point) -> Unit)? = null,
) : AutoCloseable {
    private val subject = MenuItem().apply { isEnabled = false }
    private val status = MenuItem().apply { isEnabled = false }
    private val progress = MenuItem().apply { isEnabled = false }
    private val connection = MenuItem().apply { isEnabled = false }
    private val toggle = MenuItem().apply { addActionListener { onToggle() } }
    private val finish = MenuItem("현재 세션 끝내기").apply { addActionListener { onFinish() } }
    private val skipBreak = MenuItem("휴식 건너뛰기").apply { addActionListener { onSkipBreak() } }
    private val menu = PopupMenu().apply {
        add(subject)
        add(status)
        add(progress)
        add(connection)
        addSeparator()
        add(toggle)
        add(finish)
        add(skipBreak)
        addSeparator()
        add(MenuItem("Stint 열기").apply { addActionListener { onOpen() } })
        add(MenuItem("종료").apply { addActionListener { onQuit() } })
    }
    private val tray = SystemTray.getSystemTray()
    private val icon = TrayIcon(
        renderMenuBarTimer(initial),
        initial.tooltip,
        menu.takeIf { onShowTimer == null },
    ).apply {
        // macOS preserves the image's width only when automatic square scaling is disabled.
        isImageAutoSize = false
        if (onShowTimer == null) {
            addActionListener { onOpen() }
        } else {
            addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(event: MouseEvent) {
                    if (event.button == MouseEvent.BUTTON1 || event.button == MouseEvent.BUTTON3) {
                        onShowTimer(Point(event.xOnScreen, event.yOnScreen))
                    }
                }
            })
        }
    }
    private var displayed: MenuBarTimer? = null
    private var closed = false

    init {
        check(EventQueue.isDispatchThread())
        update(initial)
        tray.add(icon)
    }

    fun update(next: MenuBarTimer) {
        check(EventQueue.isDispatchThread())
        if (closed || next == displayed) return
        icon.image = renderMenuBarTimer(next)
        icon.toolTip = next.tooltip
        subject.label = next.subjectLabel
        status.label = if (next.renderTime) "${next.statusLabel} · ${next.timeLabel}" else next.statusLabel
        progress.label = next.progressLabel
        connection.label = next.connectionLabel
        toggle.label = next.toggleLabel ?: "일시정지"
        toggle.isEnabled = next.canToggle
        finish.isEnabled = next.canFinish
        skipBreak.isEnabled = next.canSkipBreak
        displayed = next
    }

    override fun close() {
        check(EventQueue.isDispatchThread())
        if (closed) return
        closed = true
        tray.remove(icon)
    }
}
