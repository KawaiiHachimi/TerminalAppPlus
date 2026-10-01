package com.android.virtualization.terminal.new2.core

import com.google.gson.JsonParser
import java.io.File
import org.apache.commons.codec.digest.Sha2Crypt
import org.junit.Assert.*
import org.junit.Test

class CloudInitTest : com.android.virtualization.terminal.LocalizedResourcesTest() {
    @Test fun customYamlPreservesAdvancedFieldsAndCloneIdentity() {
        val source = "#cloud-config\nusers:\n  - default\npackages:\n  - htop\nruncmd:\n  - echo hello\n"
        val config = CloudInit.custom(source, "original")
        val (user, meta) = CloudInit.documents(config.copy(instanceId = "terminal-plus-clone"))
        assertEquals(source, user)
        assertEquals("terminal-plus-clone", JsonParser.parseString(meta).asJsonObject["instance-id"].asString)
        assertTrue(CloudInit.editable(config).contains("runcmd:"))
    }
    @Test fun customYamlRejectsInvalidDocuments() {
        listOf("users: []", "#cloud-config\n- list", "#cloud-config\nx: [", "#cloud-config\nx: 1\nx: 2", "#cloud-config\n!!java.net.URL [x]", "#cloud-config\na: 1\n---\nb: 2").forEach { source ->
            assertThrows(Exception::class.java) { CloudInit.custom(source, "test") }
        }
    }
    @Test fun formConvertsToEditableYamlWithHashedPassword() {
        val config = CloudInit.config("tester", "secret123", "", "", false, "test")
        val yaml = CloudInit.editable(config)
        assertTrue(yaml.contains("users:"))
        assertFalse(yaml.contains("secret123"))
        assertTrue(yaml.contains(config.passwordHash))
        CloudInit.custom(yaml, "test")
    }
    @Test fun saltedHashAndStableInstanceProduceValidSeed() {
        val first = CloudInit.config("tester", "test-password", "vm-test", "", false, "test-id")
        val second = CloudInit.config("tester", "test-password", "vm-test", "", false, "test-id")
        assertNotEquals(first.passwordHash, second.passwordHash)
        assertEquals(first.passwordHash, Sha2Crypt.sha512Crypt("test-password".toByteArray(), first.passwordHash))
        assertEquals(first.instanceId, second.instanceId)
        val (user, meta) = CloudInit.documents(first)
        assertFalse(user.contains("test-password"))
        assertTrue(user.startsWith("#cloud-config\n"))
        val parsed = JsonParser.parseString(user.removePrefix("#cloud-config\n")).asJsonObject
        assertFalse(parsed["ssh_pwauth"].asBoolean)
        assertEquals(first.passwordHash, parsed.getAsJsonArray("users")[0].asJsonObject["hashed_passwd"].asString)
        val assets = File("src/main/assets/cloud-init")
        val iso = CloudInit.fill(File(assets, "template.iso").readBytes(), File(assets, "layout.json").readText(), user, meta)
        // Filling account slots must preserve the standalone NoCloud network document.
        val network = File(assets, "network-config").readText()
        assertTrue(String(iso, Charsets.ISO_8859_1).contains(network))
        assertTrue(network.contains("name: \"en*\""))
        assertFalse(network.contains("macaddress"))
        assertFalse(network.contains("set-name"))
        File("build/cloud-init-test.iso").writeBytes(iso)
        File("build/cloud-init-test-user-data").writeText(user)
        File("build/cloud-init-test-meta-data").writeText(meta)
    }
    @Test fun rootAndKeyOnlyOptions() {
        val root = CloudInit.config("root", "example", "", "", true, "root-test")
        val data = JsonParser.parseString(CloudInit.documents(root).first.removePrefix("#cloud-config\n")).asJsonObject
        assertFalse(data["disable_root"].asBoolean)
        assertFalse(data.getAsJsonArray("users")[0].asJsonObject.has("sudo"))
        val keyOnly = CloudInit.config("tester", "", "", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGVyTsbFlUg3LPDhZYGP5KMKJFMoFgnDLoIKCvLPQVKN test", false, "key-test")
        assertEquals("", keyOnly.passwordHash)
        assertTrue(CloudInit.documents(keyOnly).first.contains("ssh_authorized_keys"))
    }
    @Test fun rejectsInvalidAndOversizedInput() {
        assertThrows(IllegalArgumentException::class.java) { CloudInit.config("bad\nuser", "x", "", "", false, "x") }
        assertThrows(IllegalArgumentException::class.java) { CloudInit.config("user", "x", "bad: host", "", false, "x") }
        assertThrows(IllegalArgumentException::class.java) { CloudInit.config("user", "", "", "", false, "x") }
        assertThrows(IllegalArgumentException::class.java) { CloudInit.config("user", "x", "", "PRIVATE KEY", false, "x") }
        assertThrows(IllegalArgumentException::class.java) { CloudInit.fill(ByteArray(10), """{"user-data":{"offset":5,"length":20}}""", "x", "x") }
    }
    @Test fun existingHashPreservedWithoutPlaintextAndBootMarkerLocks() {
        val hash = CloudInit.config("tester", "test", "", "", false, "test").passwordHash
        assertEquals(hash, CloudInit.config("tester", "", "", "", false, "test", hash).passwordHash)
        val dir = java.nio.file.Files.createTempDirectory("cloud-lock").toFile()
        try {
            assertFalse(CloudInit.locked(dir))
            File(dir, "cloud-init.booted").writeText("")
            assertTrue(CloudInit.locked(dir))
        } finally { dir.deleteRecursively() }
    }
}
