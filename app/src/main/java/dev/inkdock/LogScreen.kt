package dev.inkdock

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(name: String, text: String?, busy: Boolean, error: String?, onRefresh: () -> Unit, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var level by remember { mutableStateOf("全部") }
    var wrap by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val lines = remember(text) { text.orEmpty().lines().filter { it.isNotBlank() } }
    val visible = remember(lines, query, level) {
        lines.withIndex().filter { (_, line) ->
            line.contains(query, ignoreCase = true) && (level == "全部" || when (level) {
                "错误" -> Regex("(?i)\\b(error|fatal|exception|traceback)\\b").containsMatchIn(line)
                else -> Regex("(?i)\\b(warn|warning)\\b").containsMatchIn(line)
            })
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Column { Text("日志", style = MaterialTheme.typography.titleLarge); Text(name, style = MaterialTheme.typography.bodySmall) } },
            navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            actions = { TextButton(onClick = onRefresh, enabled = !busy) { Text("刷新") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("搜索当前日志") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("全部", "错误", "警告").forEach { item ->
                    FilterChip(selected = level == item, onClick = { level = item }, label = { Text(item) })
                }
                FilterChip(selected = wrap, onClick = { wrap = !wrap }, label = { Text("自动换行") })
                TextButton(onClick = { clipboard.setText(AnnotatedString(visible.joinToString("\n") { it.value })) }, enabled = visible.isNotEmpty()) { Text("复制筛选结果") }
            }
            Text("最近 150 行 · 手动刷新 · ${visible.size}/${lines.size} 行 · 长按可选文字",
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            if (!busy && text != null && visible.isEmpty()) Text("没有匹配的日志", Modifier.padding(16.dp))
            HorizontalDivider()
            val horizontal = rememberScrollState()
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(modifier = Modifier.fillMaxSize().then(if (wrap) Modifier else Modifier.horizontalScroll(horizontal)), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(visible, key = { _, entry -> entry.index }) { _, entry ->
                        val line = entry.value
                        val color = when {
                            Regex("(?i)\\b(error|fatal|exception|traceback)\\b").containsMatchIn(line) -> MaterialTheme.colorScheme.error
                            Regex("(?i)\\b(warn|warning)\\b").containsMatchIn(line) -> Color(0xFFC58D36)
                            Regex("(?i)\\b(debug|trace)\\b").containsMatchIn(line) -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Row {
                            Text("${entry.index + 1}".padStart(3), color = MaterialTheme.colorScheme.outline, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 12.dp))
                            Text(line, color = color, fontSize = 12.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace, modifier = if (wrap) Modifier.weight(1f) else Modifier)
                        }
                    }
                }
            }
        }
    }
}
