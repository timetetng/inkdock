package dev.inkdock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Closed command set. No extension or UI may submit shell fragments. */
object CommandPolicy {
    fun validId(id: String) = id.matches(Regex("[a-f0-9]{12,64}"))
    fun command(operation: String, id: String? = null): String {
        val runtime = "/data/local/Droidspaces/bin/droidspaces --name=arch "
        if (operation == "runtime-processes") return runtime + "run -- /usr/bin/ps -e -o cputimes=,rss=,nlwp="
        if (operation == "runtime-disk") return runtime + "run -- /usr/bin/df -B1 --output=size,used,avail,pcent /"
        if (operation == "info") return runtime + "--format info"
        if (operation == "sockets") return runtime + "run -- /usr/bin/ss -H -lntup"
        val prefix = runtime + "run -- /usr/bin/docker "
        return prefix + when (operation) {
            "list" -> "ps -a --no-trunc --format '{{json .}}'"
            "images" -> "image ls --no-trunc --digests --format '{{json .}}'"
            "pull" -> {
                require(id != null && id.length <= 255 && id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._/:@-]*"))) { "无效镜像引用" }
                "image pull $id"
            }
            "image-users", "image-remove" -> {
                require(id != null && id.matches(Regex("sha256:[a-f0-9]{64}"))) { "无效镜像 ID" }
                if (operation == "image-users") "ps -a --filter ancestor=$id --format '{{.Names}}'"
                else "image rm $id"
            }
            "stats" -> "stats --no-stream --no-trunc --format '{{json .}}'"
            "logs", "start", "stop", "top", "usage", "inspect" -> {
                require(id != null && validId(id)) { "Invalid container ID" }
                when (operation) {
                    "usage" -> "top $id -eo pid,rss,nlwp"
                    "top" -> "top $id -eo pid"
                    "inspect" -> "inspect --format '{{json .HostConfig.NetworkMode}}|{{json .NetworkSettings.Ports}}' $id"
                    "logs" -> "logs --tail 150 --timestamps $id"
                    "stop" -> "stop --time 10 $id"
                    else -> "start $id"
                }
            }
            else -> error("Unsupported operation")
        }
    }
}

data class Service(val id: String, val name: String, val state: String, val status: String, val ports: String)

data class DockerImage(val id: String, val repository: String, val tag: String, val size: String, val created: String, val digest: String, val users: List<String>)

data class ContainerStats(val cpu: String, val memory: String, val memoryPercent: String, val netIO: String, val blockIO: String, val pids: String)

data class Endpoint(val address: String, val protocol: String, val source: String, val runtimeOnly: Boolean = false)
data class NetworkDetail(val mode: String, val endpoints: List<Endpoint>, val warning: String? = null)

class DockerBridge {
    private suspend fun execute(operation: String, id: String? = null): String = withContext(Dispatchers.IO) {
        // Only validated, closed-set commands reach su. The shell is required by su;
        // arbitrary paths, container names and user text never enter this string.
        val command = CommandPolicy.command(operation, id)
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = StringBuilder()
        var truncated = false
        val reader = Thread {
            process.inputStream.bufferedReader().use { input ->
                val buffer = CharArray(2048)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    synchronized(output) {
                        output.append(buffer, 0, count)
                        if (output.length > 131072) {
                            truncated = true
                            output.delete(0, output.length - 131072)
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            check(process.waitFor(if (operation == "pull") 600 else 25, TimeUnit.SECONDS)) { "操作超时；服务端状态需刷新确认" }
            reader.join(1000)
            check(!reader.isAlive) { "输出读取未完成，请重试" }
            val text = synchronized(output) {
                check(operation == "logs" || !truncated) { "结果超过安全大小，拒绝展示不完整列表" }
                (if (truncated) "[日志已截断，仅显示末尾]\\n" else "") + output.toString()
            }
            check(process.exitValue() == 0) { text.takeLast(2000).ifBlank { "Root / Droidspaces 操作失败" } }
            text
        } finally {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
    }
    suspend fun runtimeSample(): RuntimeSample {
        val rows = execute("runtime-processes").lineSequence().map { it.trim().split(Regex("\\s+")) }.filter { it.size == 3 }.toList()
        check(rows.isNotEmpty()) { "运行时进程列表为空" }
        return RuntimeSample(android.os.SystemClock.elapsedRealtime(), rows.sumOf { it[0].toLong() }, rows.sumOf { it[1].toLong() } * 1024, rows.size, rows.sumOf { it[2].toLong() })
    }
    suspend fun runtimeDisk(): String {
        val cols = execute("runtime-disk").trim().lines().last().trim().split(Regex("\\s+"))
        check(cols.size == 4) { "磁盘统计格式异常" }
        return "已用 ${formatBytes(cols[1].toLong())} / ${formatBytes(cols[0].toLong())} · ${cols[3]}\n可用 ${formatBytes(cols[2].toLong())}"
    }
    suspend fun list(): List<Service> {
        val lines = execute("list").lineSequence().filter { it.isNotBlank() }.toList()
        return lines.map {
            check(it.trim().startsWith("{") && it.trim().endsWith("}")) { "Docker 返回非预期数据，未作为空列表处理" }
            val j = JSONObject(it)
            val id = j.getString("ID")
            check(CommandPolicy.validId(id)) { "Docker 返回无效 ID" }
            Service(id, j.getString("Names"), j.optString("State"), j.optString("Status"), j.optString("Ports"))
        }
    }
    suspend fun images(): List<DockerImage> {
        val rows = execute("images").lineSequence().filter { it.isNotBlank() }.map { JSONObject(it) }.toList()
        val users = rows.map { it.getString("ID") }.distinct().associateWith { id ->
            execute("image-users", id).lines().filter { it.isNotBlank() }
        }
        return rows.map { j -> DockerImage(j.getString("ID"), j.optString("Repository"), j.optString("Tag"), j.optString("Size"), j.optString("CreatedSince"), j.optString("Digest"), users[j.getString("ID")].orEmpty()) }
    }
    suspend fun pullImage(reference: String) { execute("pull", reference) }
    suspend fun removeImage(id: String) {
        check(execute("image-users", id).isBlank()) { "镜像仍被容器引用（包括已停止容器），拒绝删除" }
        execute("image-remove", id)
    }

    suspend fun stats(): Map<String, ContainerStats> {
        val result = execute("stats").lineSequence()
        .filter { it.isNotBlank() }.associate { line ->
            val j = JSONObject(line)
            val id = j.getString("ID")
            check(CommandPolicy.validId(id)) { "资源数据 ID 无效" }
            val memory = j.optString("MemUsage")
            val memoryUnavailable = memory.replace(" ", "") == "0B/0B"
            id to ContainerStats(j.optString("CPUPerc"), if (memoryUnavailable) "不可用" else memory,
                if (memoryUnavailable) "不可用" else j.optString("MemPerc"),
                if (j.optString("NetIO").replace(" ", "") == "0B/0B") "未报告（非真实零值）" else j.optString("NetIO"),
                if (j.optString("BlockIO").replace(" ", "") == "0B/0B") "未报告（非真实零值）" else j.optString("BlockIO"),
                j.optString("PIDs").takeUnless { it == "0" }.orEmpty().ifBlank { "不可用" })
        }.toMutableMap()
        for ((id, value) in result.toMap()) {
            val rows = execute("usage", id).lineSequence().drop(1).map { it.trim().split(Regex("\\s+")) }.filter { it.size == 3 && it[1].toLongOrNull() != null }.toList()
            if (rows.isNotEmpty()) result[id] = value.copy(
                memory = "${formatBytes(rows.sumOf { it[1].toLong() } * 1024)} RSS",
                memoryPercent = "共享页可能重复", pids = "${rows.size} / ${rows.sumOf { it[2].toLong() }}")
        }
        return result
    }

    suspend fun network(id: String): NetworkDetail {
        val parts = execute("inspect", id).trim().split('|', limit = 2)
        val mode = parts[0].trim('"')
        val info = JSONObject(execute("info"))
        val runtimeIp = info.optString("nat_ip").takeIf { it.matches(Regex("[0-9.]+")) }
            ?: return NetworkDetail(mode, emptyList(), "未获得运行时 NAT 地址，未猜测访问地址")
        if (mode == "host") {
            val pids = execute("top", id).lineSequence().map { it.trim() }.filter { it.matches(Regex("[0-9]+")) }.toSet()
            val endpoints = execute("sockets").lineSequence().mapNotNull { line ->
                val owners = Regex("pid=([0-9]+)").findAll(line).map { it.groupValues[1] }.toSet()
                if (owners.intersect(pids).isEmpty()) return@mapNotNull null
                val cols = line.trim().split(Regex("\\s+"))
                if (cols.size < 5) return@mapNotNull null
                val bind = cols[4]
                val port = bind.substringAfterLast(':').toIntOrNull() ?: return@mapNotNull null
                val host = bind.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
                val loopback = host == "127.0.0.1" || host == "::1"
                val wildcard = host == "0.0.0.0" || host == "*" || host == "::"
                val addressHost = if (wildcard) runtimeIp else host
                Endpoint("${if (addressHost.contains(':')) "[$addressHost]" else addressHost}:$port", cols[0], if (loopback) "运行时回环监听 · 非 Android localhost" else "进程归属匹配 · 实际监听", loopback)
            }.distinct().toList()
            return NetworkDetail(mode, endpoints, "列出容器进程的实际监听；回环地址属于 Droidspaces 运行时，不等于 Android 回环。未探测连通性或识别 HTTP。")
        }
        val ports = JSONObject(parts.getOrElse(1) { "{}" }.takeUnless { it == "null" } ?: "{}")
        val endpoints = mutableListOf<Endpoint>()
        for (key in ports.keys()) {
            val bindings = ports.optJSONArray(key) ?: continue
            for (i in 0 until bindings.length()) {
                val binding = bindings.getJSONObject(i)
                val host = binding.optString("HostIp")
                val port = binding.optString("HostPort").toIntOrNull() ?: continue
                if (host == "0.0.0.0" || host == "::" || host == runtimeIp) {
                    endpoints += Endpoint("$runtimeIp:$port", key.substringAfter('/'), "Docker 发布 · $key")
                }
            }
        }
        return NetworkDetail(mode, endpoints.distinct(), "发布记录不等于业务健康；仅回环绑定不冒充宿主可达。")
    }
    suspend fun logs(id: String) = execute("logs", id)
    suspend fun change(id: String, start: Boolean) { execute(if (start) "start" else "stop", id) }
}
