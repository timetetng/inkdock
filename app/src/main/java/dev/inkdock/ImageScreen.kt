package dev.inkdock

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ImageScreen(model: DockModel, modifier: Modifier = Modifier) {
    var reference by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf<DockerImage?>(null) }
    var pull by remember { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Docker 镜像", style = MaterialTheme.typography.headlineMedium) }
        item { Text("本地镜像与容器引用；大小包含共享层，不可直接相加。") }
        item { OutlinedTextField(value = reference, onValueChange = { reference = it }, singleLine = true, label = { Text("镜像引用，例如 nginx:stable") }, modifier = Modifier.fillMaxWidth()) }
        item { Row {
            TextButton(enabled = !model.imageBusy, onClick = { model.loadImages() }) { Text("刷新镜像") }
            TextButton(enabled = !model.imageBusy && reference.isNotBlank(), onClick = { pull = true }) { Text("拉取…") }
        } }
        if (model.imageBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("操作中；拉取最多等待 10 分钟。") }
        model.imageError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (model.imagesLoaded && model.images.isEmpty() && !model.imageBusy) item { Text("没有本地镜像") }
        itemsIndexed(model.images) { _, image ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${image.repository}:${image.tag}", style = MaterialTheme.typography.titleMedium)
                Text("${image.size} · ${image.created}")
                Text(image.id, style = MaterialTheme.typography.labelSmall)
                if (image.digest != "<none>") Text(image.digest, style = MaterialTheme.typography.labelSmall)
                Text(if (image.users.isEmpty()) "暂无容器引用" else "引用容器：${image.users.joinToString()}")
                TextButton(enabled = !model.imageBusy && image.users.isEmpty(), onClick = { delete = image }) { Text("删除镜像…") }
            } }
        }
    }
    delete?.let { image -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("确认删除镜像") }, text = { Text("${image.repository}:${image.tag}\n${image.id}\n不强制删除；存在多个标签或新增引用时 Docker 可能拒绝执行。") }, confirmButton = { TextButton(onClick = { model.removeImage(image.id); delete = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("取消") } }) }
    if (pull) AlertDialog(onDismissRequest = { pull = false }, title = { Text("确认拉取镜像") }, text = { Text("${reference.trim()}\n会使用当前网络、占用存储，体积由镜像决定；不会自动创建或更新容器。离开页面不取消操作。") }, confirmButton = { TextButton(onClick = { model.pullImage(reference.trim()); pull = false }) { Text("拉取") } }, dismissButton = { TextButton(onClick = { pull = false }) { Text("取消") } })
}
