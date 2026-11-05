package dev.stint.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.stint.app.ui.ClockTimeDial
import dev.stint.app.ui.Paper
import dev.stint.app.ui.TimerClockVisual
import dev.stint.app.ui.WorkbenchTheme
import dev.stint.shared.TimerClockStyle
import dev.stint.shared.countdownClockReading

/** Isolated pointer-wheel fixture; it never uses a controller, storage, or network. */
object TimeWheelFocusAcceptance {
    @JvmStatic
    fun main(args: Array<String>) = application {
        Window(onCloseRequest = ::exitApplication, title = "Stint 시간 포커스 검증",
            state = rememberWindowState(size = DpSize(540.dp, 760.dp))) {
            WorkbenchTheme {
                Surface(Modifier.fillMaxSize(), color = Paper) {
                    var seconds by remember { mutableIntStateOf(25 * 60 + 17) }
                    var key by remember { mutableStateOf("fixture-0") }
                    val activationContext = "fixture-context"
                    var commits by remember { mutableIntStateOf(0) }
                    var ticks by remember { mutableIntStateOf(0) }
                    var adjusting by remember { mutableStateOf(false) }
                    val scroll = rememberScrollState()
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp)) {
                        Text("숫자를 먼저 클릭한 뒤 스크롤하세요")
                        Spacer(Modifier.height(16.dp))
                        Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                            ClockTimeDial(seconds, 1, 14_400, true, key, activationContext, false, null,
                                onCommit = { next, _ ->
                                    commits++
                                    seconds = next
                                    key = "fixture-$commits"
                                    true
                                }, height = 280.dp, modifier = Modifier.width(360.dp),
                                onTick = { ticks++ }, onAdjustingChange = { adjusting = it }) {
                                    preview, height, timeContent ->
                                    val shown = preview ?: seconds
                                    TimerClockVisual(TimerClockStyle.ORBIT,
                                        countdownClockReading(shown, shown), false,
                                        height = height, sizingReferenceSeconds = 14_400,
                                        readoutLabel = "전체 목표", timeContent = { style ->
                                            timeContent?.invoke(this, style) ?: Text(
                                                "${shown / 60}:${(shown % 60).toString().padStart(2, '0')}",
                                                Modifier.fillMaxWidth(), style = style, textAlign = TextAlign.Center)
                                        })
                                }
                        }
                        Text("커밋: $commits · 눈금: $ticks · 조절 중: $adjusting · 화면 스크롤: ${scroll.value}")
                        Spacer(Modifier.height(1_000.dp))
                    }
                }
            }
        }
    }
}
