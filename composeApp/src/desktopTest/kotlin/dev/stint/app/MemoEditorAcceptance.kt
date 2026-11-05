package dev.stint.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.stint.app.ui.MemoEditor
import dev.stint.app.ui.Paper
import dev.stint.app.ui.WorkbenchTheme

/** Own-window keyboard fixture; never opens settings, a controller, or a network connection. */
object MemoEditorAcceptance {
    @JvmStatic
    fun main(args: Array<String>) = application {
        var saves by remember { mutableIntStateOf(0) }
        var saved by remember { mutableStateOf("") }
        Window(onCloseRequest = ::exitApplication, title = "Stint 회고 검증",
            state = rememberWindowState(size = DpSize(560.dp, 760.dp))) {
            LaunchedEffect(window) { window.toFront(); window.requestFocus() }
            WorkbenchTheme {
                Surface(Modifier.fillMaxSize(), color = Paper) {
                    Column(Modifier.fillMaxSize().padding(28.dp)) {
                        MemoEditor("fixture", "", listOf("개념 정리", "문제 풀이", "개념 정리", "오답 정리", "자료 읽기", "다음 계획", "제외될 항목"), true,
                            onSave = { saved = it; saves++ }, onCancel = ::exitApplication)
                        Spacer(Modifier.height(16.dp))
                        Text("저장 횟수: $saves")
                        Text("저장 내용: $saved")
                    }
                }
            }
        }
    }
}
