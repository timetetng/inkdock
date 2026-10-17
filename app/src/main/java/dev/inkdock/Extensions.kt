package dev.inkdock

/** Initial closed capability contract, not an executable plugin loader. */
enum class Capability { DOCKER_LIST, DOCKER_LOGS, DOCKER_START, DOCKER_STOP }
data class ExtensionManifest(
    val id: String,
    val title: String,
    val protocol: Int,
    val capabilities: Set<Capability>
) {
    fun validate() {
        require(id.matches(Regex("[a-z][a-z0-9.-]{2,63}"))) { "Invalid extension ID" }
        require(protocol == 1) { "Unsupported extension protocol" }
        require(title.length in 1..80) { "Invalid title" }
    }
}
object BuiltInExtensions {
    val docker = ExtensionManifest("inkdock.docker", "Docker", 1, Capability.entries.toSet())
}
