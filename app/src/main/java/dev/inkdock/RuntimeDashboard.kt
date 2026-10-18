package dev.inkdock

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

data class RuntimeSample(val timeMs: Long, val cpuSeconds: Long, val rssBytes: Long, val processes: Int, val threads: Long)
fun formatBytes(bytes: Long): String = String.format(Locale.US, "%.2f GiB", bytes / 1073741824.0)

@Composable
fun RuntimeDashboard(model: DockModel) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Droidspaces · arch", style = MaterialTheme.typography.headlineMedium)
        Text("整个运行时（含 Docker 与非 Docker 服务）")
        model.runtimeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CPU", style = MaterialTheme.typography.titleLarge)
            Text(model.runtimeCpu, style = MaterialTheme.typography.headlineSmall)
            Text("运行时可见进程累计 CPU 时间差；100% = 一个核心。秒级计数，仅作近似；进程退出可能导致欠计。", style = MaterialTheme.typography.bodySmall)
        } }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("内存 · 进程 RSS", style = MaterialTheme.typography.titleLarge)
            Text(model.runtimeSample?.let { formatBytes(it.rssBytes) } ?: "等待采样", style = MaterialTheme.typography.headlineSmall)
            Text(model.runtimeSample?.let { "${it.processes} 个进程 · ${it.threads} 个线程" } ?: "")
            Text("仅运行时可见进程；共享页可能重复，不含完整页缓存，不是手机总内存，也不伪造内存上限百分比。", style = MaterialTheme.typography.bodySmall)
        } }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("根文件系统 · 磁盘空间", style = MaterialTheme.typography.titleLarge)
            Text(model.runtimeDisk ?: "待读取")
            Text("df /：镜像内文件系统占用，不计绑定的手机共享存储；不是磁盘 I/O，也不代表稀疏镜像在手机上的物理体积。", style = MaterialTheme.typography.bodySmall)
        } }
        Text("前台约每 5 秒采样；磁盘约每 60 秒刷新。退出前台停止采样。", style = MaterialTheme.typography.labelSmall)
    }
}
