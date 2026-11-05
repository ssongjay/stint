package dev.stint.app.updates

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.stint.app.ui.Clay
import dev.stint.app.ui.Forest
import dev.stint.app.ui.Ink
import dev.stint.app.ui.InkMuted
import kotlinx.coroutines.launch

/** User-initiated release check. Downloading and installation stay with Android. */
@Composable
internal fun AndroidUpdateSection(
    updater: AndroidAppUpdater,
    currentVersionName: String,
) {
    val state by updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(Modifier.fillMaxWidth()) {
        Text("앱 업데이트", color = Clay, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(5.dp))
        Text("Stint $currentVersionName", color = Ink, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))

        when (val current = state) {
            AndroidUpdateState.Idle -> Text(
                "새 버전이 있는지 직접 확인할 수 있어요.",
                color = InkMuted,
                style = MaterialTheme.typography.bodyMedium,
            )

            AndroidUpdateState.Checking -> Text(
                "업데이트를 확인하고 있어요.",
                color = InkMuted,
                style = MaterialTheme.typography.bodyMedium,
            )

            AndroidUpdateState.UpToDate -> Text(
                "현재 최신 버전을 사용하고 있어요.",
                color = Forest,
                style = MaterialTheme.typography.bodyMedium,
            )

            is AndroidUpdateState.Available -> {
                Text(
                    "Stint ${current.release.versionName} 업데이트가 있어요.",
                    color = Forest,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "브라우저에서 받은 파일을 열어 설치를 확인해 주세요.",
                    color = InkMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is AndroidUpdateState.Error -> Text(
                current.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(14.dp))
        when (state) {
            is AndroidUpdateState.Available -> {
                Button(
                    onClick = { updater.openDownload(context) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("APK 받기") }
                TextButton(
                    onClick = { scope.launch { updater.check() } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("다시 확인") }
            }

            AndroidUpdateState.Checking -> OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(8.dp),
            ) { Text("확인 중…") }

            else -> OutlinedButton(
                onClick = { scope.launch { updater.check() } },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(8.dp),
            ) { Text(if (state is AndroidUpdateState.Error) "다시 확인" else "업데이트 확인") }
        }
    }
}
