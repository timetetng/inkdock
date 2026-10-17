package dev.inkdock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class DockModel : ViewModel() {
    private val bridge = DockerBridge()
    var services by mutableStateOf<List<Service>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var log by mutableStateOf<String?>(null); private set
    fun refresh() = run {
        if (busy) return@run
        viewModelScope.launch {
            busy = true; error = null
            try { services = bridge.list() } catch (e: Exception) { error = e.message ?: "读取失败" }
            finally { busy = false }
        }
    }
    fun change(service: Service) {
        if (busy) return
        viewModelScope.launch {
            busy = true; error = null
            try { bridge.change(service.id, service.state != "running"); services = bridge.list() }
            catch (e: Exception) { error = e.message ?: "操作失败，请刷新确认状态" }
            finally { busy = false }
        }
    }
    fun logs(service: Service) {
        if (busy) return
        log = null
        viewModelScope.launch {
            busy = true; error = null
            try { log = bridge.logs(service.id) }
            catch (e: Exception) { error = e.message ?: "日志读取失败" }
            finally { busy = false }
        }
    }
    fun clearLog() { log = null }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = if (dark) darkColorScheme(primary = Color(0xFF8CD5B3), surface = Color(0xFF101713))
                else lightColorScheme(primary = Color(0xFF23664C), surface = Color(0xFFF5FAF5))
            MaterialTheme(colorScheme = colors) { InkDock() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InkDock(model: DockModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Service?>(null) }
    var confirmation by remember { mutableStateOf<Service?>(null) }
    var showLog by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val tabs = listOf("概览", "服务", "网络", "文件")
    val icons = listOf(Icons.Default.Home, Icons.AutoMirrored.Filled.List, Icons.Default.Settings, Icons.Default.Folder)
    Scaffold(
        topBar = { TopAppBar(title = { Text("墨台 · ${tabs[tab]}") }, actions = {
            TextButton(onClick = { model.refresh() }, enabled = !model.busy) { Text("连接 / 刷新") }
        }) },
        bottomBar = { NavigationBar { tabs.forEachIndexed { index, title ->
            NavigationBarItem(selected = tab == index, onClick = { tab = index },
                icon = { Icon(icons[index], contentDescription = title) }, label = { Text(title) })
        } } }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (model.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            model.error?.let { error -> item { Card { Text(error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } } }
            when (tab) {
                0 -> {
                    item { Text("你的本机运维台", style = MaterialTheme.typography.headlineMedium) }
                    item { Text("P1 开发版 · 按需申请 root，不自动操作现有服务。") }
                    item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) {
                        Text("Docker", style = MaterialTheme.typography.titleLarge)
                        Text("最近一次结果：${model.services.size} 个容器")
                        Text("运行中 ${model.services.count { it.state == "running" }}")
                        Text("点击右上角连接，读取真实状态。未连接不代表没有服务。", style = MaterialTheme.typography.bodySmall)
                    } } }
                    item { Text("资源监控、mihomo 与扩展安装尚未实现。", style = MaterialTheme.typography.bodySmall) }
                }
                1 -> {
                    item { Text("Docker 服务", style = MaterialTheme.typography.headlineMedium) }
                    if (model.services.isEmpty()) item { Text("暂无已读取数据。点击连接；若失败，查看上方错误。") }
                    items(model.services, key = { it.id }) { service ->
                        Card(Modifier.fillMaxWidth().clickable { selected = service }) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(service.name, style = MaterialTheme.typography.titleMedium)
                                Text(service.status)
                                Text(service.ports.ifBlank { "无发布端口" }, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                2 -> item { Text("网络", style = MaterialTheme.typography.headlineMedium); Text("监听发现、宿主转发与代理管理尚未接入。本版不会修改网络配置。") }
                3 -> item { Text("文件", style = MaterialTheme.typography.headlineMedium); Text("容器文件浏览尚未接入。本版不会挂载镜像或修改文件。") }
            }
        }
    }
    selected?.let { service ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(service.name) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(service.status)
                Text(service.ports.ifBlank { "无发布端口" })
                Text("端口为 Docker 原始映射，不代表 Android 宿主访问地址。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { clipboard.setText(AnnotatedString(service.ports)) }) { Text("复制端口信息") }
                TextButton(enabled = !model.busy, onClick = { model.logs(service); showLog = true; selected = null }) { Text("查看最近 150 行日志") }
                TextButton(enabled = !model.busy, onClick = { confirmation = service; selected = null }) { Text(if (service.state == "running") "停止容器…" else "启动容器…") }
            }
        }, confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } })
    }
    confirmation?.let { service ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("确认${if (service.state == "running") "停止" else "启动"}") },
            text = { Text("对象：${service.name}\n可能影响此服务及其依赖。超时后需刷新确认实际状态。") },
            confirmButton = { TextButton(onClick = { model.change(service); confirmation = null }) { Text("确认执行") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } })
    }
    if (showLog) AlertDialog(onDismissRequest = { showLog = false; model.clearLog() }, title = { Text("日志 · 仅本机显示") },
        text = { LazyColumn { item { Text(model.log ?: if (model.busy) "读取中…" else "未读取成功，请查看错误。") } } },
        confirmButton = { TextButton(onClick = { showLog = false; model.clearLog() }) { Text("关闭并清除") } })
}
