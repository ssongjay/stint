package dev.stint.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val MEMO_LIMIT = 4_000

/** Defensive editor normalization; ordering is supplied by the synchronized memo history. */
internal fun memoEditorChoices(
    recentMemos: List<String>,
    currentMemo: String,
    limit: Int = 5,
): List<String> {
    if (limit <= 0) return emptyList()
    val current = currentMemo.trim()
    val seen = linkedSetOf<String>()
    return buildList {
        recentMemos.forEach { value ->
            val normalized = value.trim()
            if (normalized.isNotEmpty() && normalized != current && seen.add(normalized)) {
                add(normalized)
                if (size == limit) return@buildList
            }
        }
    }
}

internal fun memoWithCursorAtEnd(value: String): TextFieldValue =
    TextFieldValue(value, selection = TextRange(value.length))

/**
 * 회고 초안과 최근 선택 목록만 관리한다. 추천을 고르는 동작은 초안에만 반영하고,
 * 실제 서버 저장은 사용자가 저장 버튼을 눌렀을 때 onSave를 통해 상위 화면에 맡긴다.
 */
@Composable
internal fun MemoEditor(
    sessionId: String,
    initialMemo: String,
    recentMemos: List<String>,
    enabled: Boolean,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var memo by rememberSaveable(sessionId, initialMemo, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(memoWithCursorAtEnd(initialMemo))
    }
    var recentExpanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    var highlighted by rememberSaveable(sessionId) { mutableIntStateOf(0) }
    var fieldFocused by rememberSaveable(sessionId) { mutableStateOf(false) }
    val focusRequester = remember(sessionId) { FocusRequester() }
    val suggestions = memoEditorChoices(recentMemos, initialMemo)
    val latest = suggestions.firstOrNull()

    LaunchedEffect(sessionId, enabled) {
        if (enabled) focusRequester.requestFocus()
    }
    LaunchedEffect(suggestions) {
        highlighted = highlighted.coerceIn(0, suggestions.lastIndex.coerceAtLeast(0))
        if (suggestions.isEmpty()) recentExpanded = false
    }
    LaunchedEffect(recentExpanded) {
        // Expanding the inline list changes the ledger row's height. Reassert focus after that
        // layout change so the next arrow/Enter event still belongs to the memo field.
        if (recentExpanded && enabled) focusRequester.requestFocus()
    }

    // TextFieldValue에 커서 위치까지 담아야 추천을 넣은 직후 문장 끝에서 이어 쓸 수 있다.
    fun choose(value: String) {
        memo = memoWithCursorAtEnd(value.take(MEMO_LIMIT))
        recentExpanded = false
        highlighted = 0
        focusRequester.requestFocus()
    }

    fun openRecent() {
        if (suggestions.isNotEmpty()) {
            highlighted = 0
            recentExpanded = true
            focusRequester.requestFocus()
        }
    }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = memo,
            onValueChange = { next -> if (next.text.length <= MEMO_LIMIT) memo = next },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { fieldFocused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    // 한글 조합 중인 방향키·Enter를 추천 선택으로 가로채지 않는다.
                    if (!enabled || !fieldFocused || event.type != KeyEventType.KeyDown ||
                        memo.composition?.collapsed == false
                    ) return@onPreviewKeyEvent false
                    val plainKey = !event.isShiftPressed && !event.isCtrlPressed &&
                        !event.isAltPressed && !event.isMetaPressed
                    when {
                        event.key == Key.Tab && plainKey && memo.text.isEmpty() && latest != null -> {
                            choose(latest)
                            true
                        }
                        event.key == Key.DirectionDown && plainKey && suggestions.isNotEmpty() -> {
                            if (recentExpanded) highlighted = (highlighted + 1).coerceAtMost(suggestions.lastIndex)
                            else openRecent()
                            true
                        }
                        event.key == Key.DirectionUp && plainKey && recentExpanded -> {
                            highlighted = (highlighted - 1).coerceAtLeast(0)
                            true
                        }
                        (event.key == Key.Enter || event.key == Key.NumPadEnter) && plainKey && recentExpanded -> {
                            suggestions.getOrNull(highlighted)?.let(::choose)
                            true
                        }
                        event.key == Key.Escape && recentExpanded -> {
                            recentExpanded = false
                            highlighted = 0
                            true
                        }
                        else -> false
                    }
                },
            label = { Text("회고") },
            placeholder = latest?.let { suggestion ->
                {
                    Text(
                        suggestion,
                        color = InkMuted.copy(alpha = .55f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            enabled = enabled,
            // 모바일 키보드의 완료 동작은 KeyEvent 대신 IME 콜백으로 올 수도 있다.
            keyboardOptions = KeyboardOptions(imeAction = if (recentExpanded) ImeAction.Done else ImeAction.Default),
            keyboardActions = KeyboardActions(onDone = {
                if (recentExpanded) suggestions.getOrNull(highlighted)?.let(::choose)
            }),
            minLines = 2,
            maxLines = 5,
            supportingText = { Text("${memo.text.length} / $MEMO_LIMIT", color = InkMuted) },
        )

        if (suggestions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = { latest?.let(::choose) },
                    enabled = enabled && latest != null,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) { Text("직전 회고 사용") }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { if (recentExpanded) recentExpanded = false else openRecent() },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.semantics {
                        contentDescription = if (recentExpanded) "최근 회고 목록 닫기" else "최근 회고 목록 열기"
                    },
                ) {
                    Text("최근 회고 ${suggestions.size}")
                    Spacer(Modifier.width(6.dp))
                    MemoChevron(expanded = recentExpanded)
                }
            }
        }

        if (recentExpanded) {
            Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                suggestions.forEachIndexed { index, suggestion ->
                    val isHighlighted = index == highlighted
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (isHighlighted) ForestWash else Color.Transparent, RoundedCornerShape(6.dp))
                            .selectable(
                                selected = isHighlighted,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = { choose(suggestion) },
                            )
                            .padding(horizontal = 10.dp, vertical = 9.dp)
                            .clearAndSetSemantics {
                                contentDescription = "최근 회고 ${index + 1}: $suggestion"
                                this.selected = isHighlighted
                                role = Role.RadioButton
                                if (!enabled) disabled()
                                onClick(action = {
                                    if (enabled) choose(suggestion)
                                    enabled
                                })
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            suggestion,
                            color = Ink,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (index != suggestions.lastIndex) ThinRule()
                }
            }
        }

        Row {
            TextButton(onClick = { onSave(memo.text.trim()) }, enabled = enabled) { Text("저장") }
            TextButton(onClick = onCancel) { Text("취소") }
        }
    }
}

@Composable
private fun MemoChevron(expanded: Boolean) {
    Canvas(Modifier.size(12.dp)) {
        val outerY = size.height * if (expanded) .65f else .35f
        val innerY = size.height * if (expanded) .35f else .65f
        val middle = Offset(size.width * .5f, innerY)
        drawLine(Clay, Offset(size.width * .2f, outerY), middle, 1.5.dp.toPx(), StrokeCap.Round)
        drawLine(Clay, middle, Offset(size.width * .8f, outerY), 1.5.dp.toPx(), StrokeCap.Round)
    }
}
