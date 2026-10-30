package dev.stint.app

import dev.stint.shared.ClientState
import dev.stint.shared.ConnectionSettings
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.Snapshot
import dev.stint.shared.Subject
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerState
import dev.stint.shared.TimerStatus
import dev.stint.shared.elapsedMillis
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Menu
import java.awt.MenuBar
import java.awt.MenuItem
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Timer
import javax.swing.WindowConstants

/**
 * Isolated native acceptance fixture for the macOS menu-bar status item.
 *
 * It never loads the production local store, network client, or controller.
 */
object MenuBarAcceptance {
    private val paper = Color(0xF5, 0xF0, 0xE6)
    private val ink = Color(0x1B, 0x28, 0x22)
    private val clay = Color(0x9F, 0x54, 0x3D)

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("apple.awt.enableTemplateImages", "true")
        args.firstOrNull()?.let {
            ImageIO.write(contactSheet(), "png", File(it))
            System.exit(0)
        }
        EventQueue.invokeLater(::showFixture)
    }

    private fun showFixture() {
        check(EventQueue.isDispatchThread())
        if (!SystemTray.isSupported()) {
            error("This acceptance fixture requires a desktop with a system tray")
        }

        val fixture = FixtureState()
        val stateLabel = JLabel().apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, 13)
            foreground = ink
            border = BorderFactory.createEmptyBorder(12, 18, 12, 18)
        }
        var host: DesktopMenuBarHost? = null
        var ticker: Timer? = null
        var frame: JFrame? = null

        fun close() {
            ticker?.stop()
            host?.close()
            frame?.dispose()
            System.exit(0)
        }

        fun update() {
            val clientState = fixture.clientState()
            val model = menuBarTimer(clientState)
            host?.update(model)
            stateLabel.text = buildString {
                append(model.subjectLabel)
                append("  |  ")
                append(model.statusLabel)
                append("  |  ")
                append(model.timeLabel)
                append("  |  ")
                append(model.connectionLabel)
                append("  |  enabled toggle=")
                append(model.canToggle)
                append(" finish=")
                append(model.canFinish)
                append(" skip=")
                append(model.canSkipBreak)
                append("  |  callbacks open=")
                append(fixture.openCount)
                append(" toggle=")
                append(fixture.toggleCount)
                append(" finish=")
                append(fixture.finishCount)
                append(" skip=")
                append(fixture.skipCount)
            }
        }

        host = DesktopMenuBarHost(
            initial = menuBarTimer(fixture.clientState()),
            onOpen = {
                fixture.openCount += 1
                frame?.isVisible = true
                frame?.toFront()
                update()
            },
            onToggle = {
                fixture.toggleCount += 1
                fixture.toggle()
                update()
            },
            onFinish = {
                fixture.finishCount += 1
                fixture.idle()
                update()
            },
            onSkipBreak = {
                fixture.skipCount += 1
                fixture.focus()
                update()
            },
            onQuit = ::close,
        )

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 8, 8)).apply {
            isOpaque = false
            add(actionButton("집중 시작") { fixture.focus(); update() })
            add(actionButton("일시정지") { fixture.pause(); update() })
            add(actionButton("휴식") { fixture.breakTime(); update() })
            add(actionButton("스톱워치") { fixture.stopwatch(); update() })
            add(actionButton("좁은 숫자") { fixture.countdown(599); update() })
            add(actionButton("넓은 숫자") { fixture.countdown(7_200); update() })
            add(actionButton("오프라인") { fixture.offline(); update() })
            add(actionButton("대기") { fixture.idle(); update() })
            add(actionButton("종료", destructive = true, action = ::close))
        }
        val intro = JLabel(
            "격리된 메뉴 막대 QA · 네트워크, 운영 서버, 로컬 저장소를 사용하지 않아요.",
        ).apply {
            foreground = ink
            font = font.deriveFont(Font.BOLD, 15f)
            border = BorderFactory.createEmptyBorder(18, 18, 4, 18)
        }
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = paper
            add(intro)
            add(controls)
            add(stateLabel)
            add(MenuBarGallery())
        }

        fun nativeItem(label: String, action: () -> Unit): MenuItem =
            MenuItem(label).apply { addActionListener { action() } }

        val nativeMenuBar = MenuBar().apply {
            add(Menu("테스트 상태").apply {
                add(nativeItem("집중 시작") { fixture.focus(); update() })
                add(nativeItem("일시정지") { fixture.pause(); update() })
                add(nativeItem("휴식") { fixture.breakTime(); update() })
                add(nativeItem("스톱워치") { fixture.stopwatch(); update() })
                add(nativeItem("좁은 숫자") { fixture.countdown(599); update() })
                add(nativeItem("넓은 숫자") { fixture.countdown(7_200); update() })
                add(nativeItem("오프라인") { fixture.offline(); update() })
                add(nativeItem("대기") { fixture.idle(); update() })
                addSeparator()
                add(nativeItem("창 숨기기") { frame?.isVisible = false })
                add(nativeItem("종료", ::close))
            })
            val initialImage = renderMenuBarTimer(menuBarTimer(fixture.clientState()))
            add(Menu("메뉴 막대 상태").apply {
                add(MenuItem("렌더링 ${initialImage.getWidth(null)} × ${initialImage.getHeight(null)} px").apply {
                    isEnabled = false
                })
                add(MenuItem("설치된 상태 항목 ${SystemTray.getSystemTray().trayIcons.size}개").apply {
                    isEnabled = false
                })
                add(MenuItem("콜백 카운트는 실제 메뉴 막대 동작만 기록").apply {
                    isEnabled = false
                })
            })
        }

        frame = JFrame("Stint Menu Bar QA").apply {
            defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            menuBar = nativeMenuBar
            addWindowListener(object : WindowAdapter() {
                override fun windowClosing(event: WindowEvent?) = close()
            })
            contentPane.layout = BorderLayout()
            contentPane.add(JScrollPane(content).apply { border = null }, BorderLayout.CENTER)
            minimumSize = Dimension(860, 680)
            setSize(980, 760)
            setLocationRelativeTo(null)
            isVisible = true
        }
        ticker = Timer(250) { update() }.apply {
            isCoalesce = true
            start()
        }
        update()
    }

    private fun actionButton(
        label: String,
        destructive: Boolean = false,
        action: () -> Unit,
    ): JButton = JButton(label).apply {
        foreground = if (destructive) clay else ink
        isFocusPainted = false
        addActionListener { action() }
    }

    private class FixtureState {
        private val subject = Subject(id = "qa-focus", name = "집중하기")
        private var connection = ConnectionStatus.CONNECTED
        private var timer = runningTimer(
            phase = TimerPhase.FOCUS,
            mode = TimerMode.COUNTDOWN,
            targetSeconds = 1_500,
            accumulatedMillis = 456,
        )

        var openCount = 0
        var toggleCount = 0
        var finishCount = 0
        var skipCount = 0

        fun clientState(now: Long = System.currentTimeMillis()): ClientState = ClientState(
            settings = ConnectionSettings(deviceName = "메뉴 막대 QA"),
            snapshot = Snapshot(
                serverNowEpochMs = now,
                subjects = listOf(subject),
                timer = timer,
            ),
            serverNowEpochMs = now,
            connection = connection,
        )

        fun focus() {
            connection = ConnectionStatus.CONNECTED
            timer = runningTimer(TimerPhase.FOCUS, TimerMode.COUNTDOWN, 1_500, 456)
        }

        fun breakTime() {
            connection = ConnectionStatus.CONNECTED
            timer = runningTimer(TimerPhase.SHORT_BREAK, TimerMode.COUNTDOWN, 300, 70_123)
        }

        fun stopwatch() {
            connection = ConnectionStatus.CONNECTED
            timer = runningTimer(TimerPhase.FOCUS, TimerMode.STOPWATCH, 1_500, 3_661_234)
        }

        fun countdown(seconds: Int) {
            connection = ConnectionStatus.CONNECTED
            timer = TimerState(
                id = "qa-timer",
                subjectId = subject.id,
                status = TimerStatus.PAUSED,
                phase = TimerPhase.FOCUS,
                mode = TimerMode.COUNTDOWN,
                targetSeconds = seconds,
                accumulatedMillis = 0,
            )
        }

        fun pause() {
            if (timer.status != TimerStatus.RUNNING) return
            val now = System.currentTimeMillis()
            timer = timer.copy(
                status = TimerStatus.PAUSED,
                accumulatedMillis = timer.elapsedMillis(now),
                runningSinceEpochMs = null,
            )
        }

        fun toggle() {
            val now = System.currentTimeMillis()
            timer = when (timer.status) {
                TimerStatus.RUNNING -> timer.copy(
                    status = TimerStatus.PAUSED,
                    accumulatedMillis = timer.elapsedMillis(now),
                    runningSinceEpochMs = null,
                )
                TimerStatus.PAUSED -> timer.copy(
                    status = TimerStatus.RUNNING,
                    runningSinceEpochMs = now,
                )
                TimerStatus.IDLE -> timer
            }
        }

        fun offline() {
            connection = ConnectionStatus.OFFLINE
        }

        fun idle() {
            connection = ConnectionStatus.CONNECTED
            timer = TimerState(subjectId = subject.id, status = TimerStatus.IDLE)
        }

        private fun runningTimer(
            phase: TimerPhase,
            mode: TimerMode,
            targetSeconds: Int,
            accumulatedMillis: Long,
        ): TimerState = TimerState(
            id = "qa-timer",
            subjectId = subject.id,
            status = TimerStatus.RUNNING,
            phase = phase,
            mode = mode,
            targetSeconds = targetSeconds,
            accumulatedMillis = accumulatedMillis,
            runningSinceEpochMs = System.currentTimeMillis(),
        )
    }

    private class MenuBarGallery : JPanel(GridLayout(0, 2, 12, 12)) {
        private val samples = representativeModels()

        init {
            background = paper
            border = BorderFactory.createEmptyBorder(8, 18, 22, 18)
            samples.forEach { (label, model) -> add(SamplePanel(label, model)) }
        }
    }

    private class SamplePanel(
        private val label: String,
        private val model: MenuBarTimer,
    ) : JPanel() {
        init {
            preferredSize = Dimension(420, 142)
            minimumSize = preferredSize
            isOpaque = true
        }

        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            val g = graphics.create() as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = paper
            g.fillRect(0, 0, width, height / 2)
            g.color = ink
            g.fillRect(0, height / 2, width, height - height / 2)
            g.font = font.deriveFont(Font.BOLD, 13f)
            g.color = ink
            g.drawString(label, 12, 20)

            val source = oneX(renderMenuBarTimer(model))
            g.drawImage(source, 12, 29, null)
            g.drawImage(source, width - source.width * 3 - 12, 5, source.width * 3, source.height * 3, null)

            val light = tint(source, paper)
            g.color = paper
            g.drawString(model.tooltip, 12, height / 2 + 21)
            g.drawImage(light, 12, height / 2 + 31, null)
            g.drawImage(
                light,
                width - light.width * 3 - 12,
                height / 2 + 4,
                light.width * 3,
                light.height * 3,
                null,
            )
            g.dispose()
        }
    }

    private fun representativeModels(): List<Pair<String, MenuBarTimer>> {
        val now = 10_000L
        fun model(
            status: TimerStatus,
            phase: TimerPhase = TimerPhase.FOCUS,
            mode: TimerMode = TimerMode.COUNTDOWN,
            target: Int = 1_500,
            elapsed: Long = 0,
            connection: ConnectionStatus = ConnectionStatus.CONNECTED,
        ): MenuBarTimer = menuBarTimer(
            ClientState(
                settings = ConnectionSettings(),
                snapshot = Snapshot(
                    serverNowEpochMs = now,
                    subjects = listOf(Subject("sample", "집중하기")),
                    timer = TimerState(
                        id = if (status == TimerStatus.IDLE) null else "sample-timer",
                        subjectId = "sample",
                        status = status,
                        phase = phase,
                        mode = mode,
                        targetSeconds = target,
                        accumulatedMillis = elapsed,
                    ),
                ),
                serverNowEpochMs = now,
                connection = connection,
            ),
        )
        return listOf(
            "집중 · 진행" to model(TimerStatus.RUNNING, elapsed = 345_000),
            "집중 · 일시정지" to model(TimerStatus.PAUSED, elapsed = 345_600),
            "짧은 휴식" to model(TimerStatus.RUNNING, TimerPhase.SHORT_BREAK, target = 300, elapsed = 90_000),
            "스톱워치" to model(TimerStatus.RUNNING, mode = TimerMode.STOPWATCH, elapsed = 3_661_000),
            "오프라인" to model(TimerStatus.RUNNING, connection = ConnectionStatus.OFFLINE),
            "대기" to model(TimerStatus.IDLE),
        )
    }

    private fun contactSheet(): BufferedImage {
        val width = 1_000
        val rowHeight = 96
        val samples = representativeModels()
        return BufferedImage(width, rowHeight * samples.size, BufferedImage.TYPE_INT_ARGB).also { image ->
            val g = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            samples.forEachIndexed { index, (label, model) ->
                val y = index * rowHeight
                g.color = if (index % 2 == 0) paper else ink
                g.fillRect(0, y, width, rowHeight)
                g.font = Font(Font.SANS_SERIF, Font.BOLD, 18)
                g.color = if (index % 2 == 0) ink else paper
                g.drawString(label, 22, y + 30)
                val source = oneX(renderMenuBarTimer(model))
                val display = if (index % 2 == 0) source else tint(source, paper)
                g.drawImage(display, 230, y + 15, display.width * 3, display.height * 3, null)
                g.font = Font(Font.SANS_SERIF, Font.PLAIN, 14)
                g.drawString(model.tooltip, 520, y + 31)
            }
            g.dispose()
        }
    }

    private fun oneX(image: java.awt.Image): BufferedImage {
        val source = (image as? MultiResolutionImage)?.resolutionVariants?.firstOrNull() ?: image
        if (source is BufferedImage) return source
        return BufferedImage(source.getWidth(null), source.getHeight(null), BufferedImage.TYPE_INT_ARGB).also {
            val graphics = it.createGraphics()
            graphics.drawImage(source, 0, 0, null)
            graphics.dispose()
        }
    }

    private fun tint(source: BufferedImage, color: Color): BufferedImage =
        BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB).also { target ->
            for (y in 0 until source.height) {
                for (x in 0 until source.width) {
                    val alpha = source.getRGB(x, y) ushr 24
                    target.setRGB(x, y, alpha shl 24 or (color.rgb and 0x00FF_FFFF))
                }
            }
        }
}
