package dev.inkdock

import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    @Test fun validContainerId() {
        assertTrue(CommandPolicy.validId("012345abcdef"))
        assertFalse(CommandPolicy.validId("abc; reboot"))
        assertFalse(CommandPolicy.validId("--help"))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsInjection() { CommandPolicy.command("stop", "abc; reboot") }
    @Test(expected = IllegalStateException::class)
    fun rejectsUnknownAction() { CommandPolicy.command("shell") }
    @Test fun builtInManifestIsValid() { BuiltInExtensions.docker.validate() }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownProtocol() {
        ExtensionManifest("test.demo", "Demo", 99, emptySet()).validate()
    }
}
