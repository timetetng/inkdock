package dev.inkdock

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

class DockModel : ViewModel() {
    private val bridge = DockerBridge()
    var services by mutableStateOf<List<Service>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var log by mutableStateOf<String?>(null); private set
    var stats by mutableStateOf<Map<String, ContainerStats>>(emptyMap()); private set
    var statsError by mutableStateOf<String?>(null); private set
    var runtimeSample by mutableStateOf<RuntimeSample?>(null); private set
    var runtimeCpu by mutableStateOf("等待第二次采样"); private set
    var runtimeDisk by mutableStateOf<String?>(null); private set
    var runtimeError by mutableStateOf<String?>(null); private set
    private var diskUpdated = 0L
    suspend fun updateRuntime() {
        try {
            val next = bridge.runtimeSample()
            val previous = runtimeSample
            runtimeCpu = if (previous != null && next.timeMs > previous.timeMs && next.cpuSeconds >= previous.cpuSeconds)
                java.lang.String.format(java.util.Locale.US, "%.1f%%", (next.cpuSeconds - previous.cpuSeconds) * 100000.0 / (next.timeMs - previous.timeMs)) else "等待有效时间差"
            runtimeSample = next
            if (runtimeDisk == null || next.timeMs - diskUpdated >= 60000) {
                runtimeDisk = bridge.runtimeDisk(); diskUpdated = next.timeMs
            }
            runtimeError = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { runtimeError = e.message ?: "运行时采样失败" }
    }
    suspend fun updateStats() {
        if (busy || services.isEmpty()) return
        try { stats = bridge.stats(); statsError = null }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { statsError = e.message ?: "资源读取失败"; stats = emptyMap() }
    }
    var networks by mutableStateOf<Map<String, NetworkDetail>>(emptyMap()); private set
    var networkErrors by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var network by mutableStateOf<NetworkDetail?>(null); private set
    var networkError by mutableStateOf<String?>(null); private set
    fun network(service: Service) {
        network = networks[service.id]; networkError = networkErrors[service.id]
        viewModelScope.launch {
            try {
                val result = bridge.network(service.id)
                networks = networks + (service.id to result)
                networkErrors = networkErrors - service.id
                network = result; networkError = null
            }
            catch (e: Exception) {
                val message = e.message ?: "监听读取失败"
                networkErrors = networkErrors + (service.id to message)
                networkError = message
            }
        }
    }
    fun refresh() = run {
        if (busy) return@run
        viewModelScope.launch {
            busy = true; error = null
            try {
                services = bridge.list()
                networks = emptyMap(); networkErrors = emptyMap()
                for (service in services.filter { it.state == "running" }) {
                    try { networks = networks + (service.id to bridge.network(service.id)) }
                    catch (e: Exception) { networkErrors = networkErrors + (service.id to (e.message ?: "监听读取失败")) }
                }
            } catch (e: Exception) { error = e.message ?: "读取失败" }
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
    var images by mutableStateOf<List<DockerImage>>(emptyList()); private set
    var imagesLoaded by mutableStateOf(false); private set
    var imageBusy by mutableStateOf(false); private set
    var imageError by mutableStateOf<String?>(null); private set
    private fun imageAction(action: suspend () -> Unit = {}) {
        if (imageBusy) return
        imageBusy = true; imageError = null
        viewModelScope.launch {
            try { action(); images = bridge.images(); imagesLoaded = true }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { imageError = e.message ?: "镜像操作失败，请刷新确认实际状态" }
            finally { imageBusy = false }
        }
    }
    fun loadImages() = imageAction()
    fun pullImage(reference: String) = imageAction { bridge.pullImage(reference) }
    fun removeImage(id: String) = imageAction { bridge.removeImage(id) }
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
    var logService by remember { mutableStateOf<Service?>(null) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, tab) {
        if (tab == 0 || tab == 1) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { if (tab == 0) model.updateRuntime() else model.updateStats(); delay(5000) }
        }
    }
    val currentLog = logService
    BackHandler(enabled = currentLog != null) { logService = null; model.clearLog() }
    if (currentLog != null) {
        LogScreen(currentLog.name, model.log, model.busy, model.error,
            onRefresh = { model.logs(currentLog) },
            onBack = { logService = null; model.clearLog() })
        return
    }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(tab) { if (tab == 2 && !model.imagesLoaded) model.loadImages() }
    val tabs = listOf("概览", "服务", "镜像", "网络", "文件")
    val icons = listOf(Icons.Default.Home, Icons.AutoMirrored.Filled.List, Icons.Default.Storage, Icons.Default.Settings, Icons.Default.Folder)
    Scaffold(
        topBar = { TopAppBar(title = { Text("墨台 · ${tabs[tab]}") }, actions = {
            TextButton(onClick = { if (tab == 2) model.loadImages() else model.refresh() }, enabled = !model.busy && !model.imageBusy) { Text("连接 / 刷新") }
        }) },
        bottomBar = { NavigationBar { tabs.forEachIndexed { index, title ->
            NavigationBarItem(selected = tab == index, onClick = { tab = index },
                icon = { Icon(icons[index], contentDescription = title) }, label = { Text(title) })
        } } }
    ) { padding ->
        if (tab == 2) ImageScreen(model, Modifier.padding(padding))
        else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (model.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            model.error?.let { error -> item { Card { Text(error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } } }
            if (tab == 0 || tab == 1) {
                item { Text("资源：前台每 5 秒采样 · CPU / 内存", style = MaterialTheme.typography.labelSmall) }
                model.statsError?.let { item { Text("资源读取失败：$it", color = MaterialTheme.colorScheme.error) } }
            }
            when (tab) {
                0 -> {
                    item { RuntimeDashboard(model) }
                    item { Text("P1 开发版 · 按需申请 root，不自动操作现有服务。") }
                    item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) {
                        Text("Docker", style = MaterialTheme.typography.titleLarge)
                        Text("最近一次结果：${model.services.size} 个容器")
                        Text("运行中 ${model.services.count { it.state == "running" }}")
                        Text("点击右上角连接，读取真实状态。未连接不代表没有服务。", style = MaterialTheme.typography.bodySmall)
                    } } }

                }
                1 -> {
                    item { Text("Docker 服务", style = MaterialTheme.typography.headlineMedium) }
                    if (model.services.isEmpty()) item { Text("暂无已读取数据。点击连接；若失败，查看上方错误。") }
                    items(model.services, key = { it.id }) { service ->
                        Card(Modifier.fillMaxWidth().clickable { selected = service; model.network(service) }) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(service.name, style = MaterialTheme.typography.titleMedium)
                                Text(service.status)
                                val usage = model.stats[service.id]
                                if (usage != null) {
                                    Text("CPU ${usage.cpu}  ·  内存 ${usage.memory} (${usage.memoryPercent})")
                                    Text("进程/线程 ${usage.pids}", style = MaterialTheme.typography.labelSmall)
                                } else Text(if (service.state == "running") "资源数据待读取或不可用" else "未运行 · 无实时资源数据", style = MaterialTheme.typography.bodySmall)
                                val net = model.networks[service.id]
                                if (net != null) {
                                    Text("网络：${net.mode}", style = MaterialTheme.typography.labelSmall)
                                    net.endpoints.forEach { endpoint -> Text("${endpoint.protocol} ${endpoint.address}${if (endpoint.runtimeOnly) " · 运行时回环" else ""}", style = MaterialTheme.typography.bodySmall) }
                                    if (net.endpoints.isEmpty()) Text("未发现监听或发布记录", style = MaterialTheme.typography.bodySmall)
                                } else Text(model.networkErrors[service.id] ?: if (service.state == "running") "监听待读取" else "未运行", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                3 -> item { Text("网络", style = MaterialTheme.typography.headlineMedium); Text("监听发现、宿主转发与代理管理尚未接入。本版不会修改网络配置。") }
                4 -> item { Text("文件", style = MaterialTheme.typography.headlineMedium); Text("容器文件浏览尚未接入。本版不会挂载镜像或修改文件。") }
            }
        }
    }
    selected?.let { service ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(service.name) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(service.status)
                model.stats[service.id]?.let { usage ->
                    Text("CPU ${usage.cpu} · 内存 ${usage.memory} (${usage.memoryPercent})")
                    Text("进程/线程 ${usage.pids}", style = MaterialTheme.typography.bodySmall)
                }

                model.networkErrors[service.id]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val detail = model.networks[service.id]
                if (detail == null && model.networkError == null) Text("正在发现监听…")
                detail?.let { network ->
                    Text("网络模式：${network.mode}")
                    if (network.endpoints.isEmpty()) Text("未发现监听或发布记录。")
                    Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        network.endpoints.forEach { endpoint ->
                            Text(endpoint.address, style = MaterialTheme.typography.titleSmall)
                            Text("${endpoint.protocol} · ${endpoint.source}", style = MaterialTheme.typography.labelSmall)
                            Row {
                                TextButton(onClick = { clipboard.setText(AnnotatedString(endpoint.address)) }) { Text("复制地址") }
                                if (endpoint.protocol == "tcp" && !endpoint.runtimeOnly) TextButton(onClick = {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://${endpoint.address}"))) }
                                }) { Text("尝试 HTTP") }
                            }
                        }
                    }
                    network.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                TextButton(enabled = !model.busy, onClick = { model.logs(service); logService = service; selected = null }) { Text("打开日志查看器") }
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

}
