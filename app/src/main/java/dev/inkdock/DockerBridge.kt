package dev.inkdock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Closed command set. No extension or UI may submit shell fragments. */
object CommandPolicy {
    fun validId(id: String) = id.matches(Regex("[a-f0-9]{12,64}"))
    fun command(operation: String, id: String? = null): String {
        val prefix = "/data/local/Droidspaces/bin/droidspaces --name=arch run -- /usr/bin/docker "
        return prefix + when (operation) {
            "list" -> "ps -a --no-trunc --format '{{json .}}'"
            "logs", "start", "stop" -> {
                require(id != null && validId(id)) { "Invalid container ID" }
                when (operation) {
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
            check(process.waitFor(25, TimeUnit.SECONDS)) { "操作超时；服务端状态需刷新确认" }
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
    suspend fun logs(id: String) = execute("logs", id)
    suspend fun change(id: String, start: Boolean) { execute(if (start) "start" else "stop", id) }
}
