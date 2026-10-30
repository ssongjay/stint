package dev.stint.app

import dev.stint.shared.ClientState
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerStatus
import dev.stint.shared.countdownClockReading
import dev.stint.shared.elapsedMillis
import dev.stint.shared.formatClockTime
import dev.stint.shared.stopwatchClockReading
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class MenuBarTimer(
    val title: String,
    val tooltip: String,
    val subjectLabel: String,
    val statusLabel: String,
    val timeLabel: String,
    val progressLabel: String,
    val connectionLabel: String,
    val toggleLabel: String?,
    val canToggle: Boolean,
    val canFinish: Boolean,
    val canSkipBreak: Boolean,
    val renderTime: Boolean,
    val progressFraction: Float,
    val phase: TimerPhase?,
    val status: TimerStatus?,
    val mode: TimerMode?,
    val disconnected: Boolean,
)

internal fun menuBarTimer(state: ClientState): MenuBarTimer {
    val connectionLabel = when (state.connection) {
        ConnectionStatus.SETUP -> "연결 필요"
        ConnectionStatus.CONNECTING -> "동기화 중"
        ConnectionStatus.CONNECTED -> "동기화됨"
        ConnectionStatus.OFFLINE -> "오프라인"
    }
    val snapshot = state.snapshot
    val connectionIsLive = state.connection == ConnectionStatus.CONNECTED && snapshot != null
    if (!connectionIsLive) {
        val statusLabel = when (state.connection) {
            ConnectionStatus.SETUP -> "서버 연결을 설정해 주세요"
            ConnectionStatus.CONNECTING -> "최신 상태를 불러오는 중"
            ConnectionStatus.CONNECTED -> "최신 상태를 불러오는 중"
            ConnectionStatus.OFFLINE -> "연결이 끊겨 최신 시간을 표시할 수 없어요"
        }
        return MenuBarTimer(
            title = "Stint",
            tooltip = "Stint · $statusLabel",
            subjectLabel = "Stint",
            statusLabel = statusLabel,
            timeLabel = "—",
            progressLabel = if (state.connection == ConnectionStatus.SETUP) {
                "Stint를 열어 서버를 연결해 주세요"
            } else {
                "연결이 복구되면 표시가 다시 이어져요"
            },
            connectionLabel = if (snapshot == null && state.connection == ConnectionStatus.CONNECTED) {
                "불러오는 중"
            } else {
                connectionLabel
            },
            toggleLabel = null,
            canToggle = false,
            canFinish = false,
            canSkipBreak = false,
            renderTime = false,
            progressFraction = 0f,
            phase = null,
            status = null,
            mode = null,
            disconnected = state.connection == ConnectionStatus.OFFLINE,
        )
    }

    val timer = snapshot.timer
    if (timer.status == TimerStatus.IDLE) {
        return MenuBarTimer(
            title = "Stint",
            tooltip = "Stint · 대기 중 · $connectionLabel",
            subjectLabel = "현재 작업 없음",
            statusLabel = "대기 중",
            timeLabel = "—",
            progressLabel = "타이머를 시작하면 여기에 표시돼요",
            connectionLabel = connectionLabel,
            toggleLabel = null,
            canToggle = false,
            canFinish = false,
            canSkipBreak = false,
            renderTime = false,
            progressFraction = 0f,
            phase = timer.phase,
            status = timer.status,
            mode = timer.mode,
            disconnected = false,
        )
    }

    val subject = snapshot.subjects.firstOrNull { it.id == timer.subjectId }
    val subjectLabel = subject?.name?.takeIf { it.isNotBlank() } ?: "이름 없는 작업"
    val elapsedMillis = timer.elapsedMillis(state.serverNowEpochMs)
    val elapsedSeconds = (elapsedMillis / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val phaseLabel = timer.phase.label()
    val runningLabel = if (timer.status == TimerStatus.PAUSED) "일시정지" else "진행 중"
    val statusLabel = "$phaseLabel · $runningLabel"

    val timeLabel: String
    val progressLabel: String
    val progressFraction: Float
    if (timer.mode == TimerMode.COUNTDOWN) {
        val targetMillis = timer.targetSeconds.coerceAtLeast(0).toLong() * 1_000L
        val remainingMillis = (targetMillis - elapsedMillis).coerceAtLeast(0)
        val remainingSeconds = ceil(remainingMillis / 1_000.0)
            .coerceAtMost(Int.MAX_VALUE.toDouble())
            .toInt()
        timeLabel = formatCountdown(remainingSeconds)
        progressLabel = "남음 $timeLabel · 경과 ${formatCountdown(elapsedSeconds)} / ${formatCountdown(timer.targetSeconds)}"
        progressFraction = countdownClockReading(remainingSeconds, timer.targetSeconds).ringFraction
    } else {
        timeLabel = formatClockTime(elapsedSeconds)
        progressLabel = "경과 $timeLabel"
        progressFraction = stopwatchClockReading(elapsedSeconds).ringFraction
    }

    val canMutate = state.connection == ConnectionStatus.CONNECTED && !state.working
    val toggleLabel = when (timer.status) {
        TimerStatus.RUNNING -> "일시정지"
        TimerStatus.PAUSED -> "계속하기"
        TimerStatus.IDLE -> null
    }
    val timeDescription = if (timer.mode == TimerMode.COUNTDOWN) "$timeLabel 남음" else "$timeLabel 경과"
    return MenuBarTimer(
        title = timeLabel,
        tooltip = "Stint · $subjectLabel · $statusLabel · $timeDescription · $connectionLabel",
        subjectLabel = subjectLabel,
        statusLabel = statusLabel,
        timeLabel = timeLabel,
        progressLabel = progressLabel,
        connectionLabel = connectionLabel,
        toggleLabel = toggleLabel,
        canToggle = canMutate && toggleLabel != null,
        canFinish = canMutate && timer.phase == TimerPhase.FOCUS,
        canSkipBreak = canMutate && timer.phase != TimerPhase.FOCUS,
        renderTime = true,
        progressFraction = progressFraction.coerceIn(0f, 1f),
        phase = timer.phase,
        status = timer.status,
        mode = timer.mode,
        disconnected = false,
    )
}

internal fun renderMenuBarTimer(model: MenuBarTimer): Image {
    val layout = menuBarLayout(model)
    val oneX = renderMenuBarTimer(model, layout, scale = 1)
    val twoX = renderMenuBarTimer(model, layout, scale = 2)
    return BaseMultiResolutionImage(oneX, twoX)
}

private data class MenuBarLayout(
    val width: Int,
    val height: Int,
    val iconSize: Int,
    val gap: Int,
    val horizontalPadding: Int,
    val font: Font,
    val timeSlots: Int,
    val digitAdvance: Int,
)

private fun menuBarLayout(model: MenuBarTimer): MenuBarLayout {
    val font = menuBarFont(14f)
    val metricsImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    val metrics = metricsImage.createGraphics().run {
        this.font = font
        val result = fontMetrics
        dispose()
        result
    }
    val iconSize = 20
    val gap = 4
    val horizontalPadding = 2
    val timeSlots = when (model.mode) {
        TimerMode.COUNTDOWN -> maxOf(6, model.timeLabel.length)
        TimerMode.STOPWATCH -> maxOf(8, model.timeLabel.length)
        null -> 0
    }
    val digitAdvance = ('0'..'9').maxOf { metrics.charWidth(it) }.coerceAtLeast(metrics.charWidth(':'))
    val textWidth = if (model.renderTime) {
        digitAdvance * timeSlots
    } else {
        metrics.stringWidth(model.title)
    }
    val width = horizontalPadding * 2 + iconSize + gap + textWidth
    return MenuBarLayout(
        width = width,
        height = 22,
        iconSize = iconSize,
        gap = gap,
        horizontalPadding = horizontalPadding,
        font = font,
        timeSlots = timeSlots,
        digitAdvance = digitAdvance,
    )
}

private fun renderMenuBarTimer(model: MenuBarTimer, layout: MenuBarLayout, scale: Int): BufferedImage {
    return BufferedImage(layout.width * scale, layout.height * scale, BufferedImage.TYPE_INT_ARGB).also { image ->
        val graphics = image.createGraphics()
        graphics.scale(scale.toDouble(), scale.toDouble())
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.color = Color.BLACK
        drawStatusIcon(
            graphics,
            model,
            layout.horizontalPadding,
            (layout.height - layout.iconSize) / 2,
            layout.iconSize,
        )

        graphics.font = layout.font
        val metrics = graphics.fontMetrics
        val baseline = (layout.height - metrics.height) / 2 + metrics.ascent
        if (model.renderTime) {
            val text = model.timeLabel.padStart(layout.timeSlots)
            val startX = layout.width - layout.horizontalPadding - layout.digitAdvance * layout.timeSlots
            text.forEachIndexed { index, character ->
                if (character != ' ') {
                    val characterWidth = metrics.charWidth(character)
                    val characterX = startX + index * layout.digitAdvance + (layout.digitAdvance - characterWidth) / 2
                    graphics.drawString(character.toString(), characterX, baseline)
                }
            }
        } else {
            graphics.drawString(
                model.title,
                layout.horizontalPadding + layout.iconSize + layout.gap,
                baseline,
            )
        }
        graphics.dispose()
    }
}

private fun drawStatusIcon(
    graphics: Graphics2D,
    model: MenuBarTimer,
    x: Int,
    y: Int,
    size: Int,
) {
    val inset = 2
    val diameter = size - inset * 2
    graphics.stroke = BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    graphics.color = Color(0, 0, 0, 144)
    graphics.drawOval(x + inset, y + inset, diameter, diameter)
    graphics.color = Color.BLACK

    if (model.renderTime) {
        val sweep = (-360f * model.progressFraction).roundToInt()
        if (sweep != 0) graphics.drawArc(x + inset, y + inset, diameter, diameter, 90, sweep)
        val centerX = x + size / 2
        val centerY = y + size / 2
        when {
            model.status == TimerStatus.PAUSED -> {
                val barHeight = 8
                graphics.fillRoundRect(centerX - 3, centerY - barHeight / 2, 2, barHeight, 1, 1)
                graphics.fillRoundRect(centerX + 1, centerY - barHeight / 2, 2, barHeight, 1, 1)
            }
            model.mode == TimerMode.STOPWATCH -> {
                graphics.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                graphics.drawLine(centerX, centerY - 4, centerX, centerY)
                graphics.drawLine(centerX, centerY, centerX + 3, centerY + 2)
            }
            model.phase == TimerPhase.FOCUS -> graphics.fillOval(
                centerX - 2,
                centerY - 2,
                4,
                4,
            )
            else -> {
                graphics.fillOval(centerX - 4, centerY - 4, 8, 8)
                graphics.color = Color(0, 0, 0, 0)
                graphics.composite = java.awt.AlphaComposite.Clear
                graphics.fillOval(centerX, centerY - 4, 7, 7)
                graphics.composite = java.awt.AlphaComposite.SrcOver
                graphics.color = Color.BLACK
            }
        }

    } else {
        graphics.fillOval(x + size / 2 - 2, y + size / 2 - 2, 4, 4)
    }

    if (model.disconnected) {
        graphics.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        graphics.drawLine(x + 3, y + size - 3, x + size - 3, y + 3)
    }
}

private fun menuBarFont(size: Float): Font {
    val preferred = Font("Helvetica Neue", Font.BOLD, size.roundToInt())
    return if (preferred.family == "Dialog") Font(Font.SANS_SERIF, Font.BOLD, size.roundToInt()) else preferred
}

private fun formatCountdown(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val minutes = (safe / 60).toString().padStart(2, '0')
    val tail = (safe % 60).toString().padStart(2, '0')
    return "$minutes:$tail"
}

private fun TimerPhase.label(): String = when (this) {
    TimerPhase.FOCUS -> "집중"
    TimerPhase.SHORT_BREAK -> "짧은 휴식"
    TimerPhase.LONG_BREAK -> "긴 휴식"
}
