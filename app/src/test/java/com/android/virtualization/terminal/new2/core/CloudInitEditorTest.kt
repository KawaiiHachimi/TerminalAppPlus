package com.android.virtualization.terminal.new2.core

import org.junit.Assert.*
import org.junit.Test

class CloudInitEditorTest : com.android.virtualization.terminal.LocalizedResourcesTest() {
    private val source = """#cloud-config
# custom configuration
users:
  - name: alice
    groups: [wheel, video]
    sudo: false
    lock_passwd: true
  - name: bob
    shell: /bin/zsh
packages: [htop]
runcmd:
  - [echo, hello]
write_files:
  - path: /etc/example
    content: |
      first line
      second line
ssh_pwauth: false
"""
    @Test fun formPatchesOnlySelectedFieldAndKeepsAdvancedNodes() {
        val edited = CloudInitEditor.patch(source, "name", "charlie")
        val yaml = org.yaml.snakeyaml.Yaml()
        val before = yaml.load<Map<String, Any>>(source).toMutableMap()
        val after = yaml.load<Map<String, Any>>(edited).toMutableMap()
        val oldUsers = before.remove("users") as List<Map<String, Any>>
        val newUsers = after.remove("users") as List<Map<String, Any>>
        assertEquals(before, after)
        assertEquals(oldUsers[0] + ("name" to "charlie"), newUsers[0])
        assertEquals(oldUsers[1], newUsers[1])
        assertTrue(edited.contains("# custom configuration"))
        assertEquals("charlie", CloudInitEditor.read(edited)?.username)
    }
    @Test fun editingAnAnchoredUserDoesNotChangeOtherAliases() {
        val source = "#cloud-config\nusers:\n  - &account {name: alice, groups: [wheel]}\n  - *account\ncustom: *account\n"
        val changed = CloudInitEditor.patch(source, "name", "bob")
        val data = org.yaml.snakeyaml.Yaml().load<Map<String, Any>>(changed)
        val users = data["users"] as List<*>
        assertEquals("bob", (users[0] as Map<*, *>)["name"])
        assertEquals("alice", (users[1] as Map<*, *>)["name"])
        assertEquals("alice", (data["custom"] as Map<*, *>)["name"])
    }
    @Test fun commonAccountFieldsRoundTripWithoutTouchingCustomFields() {
        var text = source
        for ((field, value) in listOf("primary_group" to "users", "groups" to "users, wheel", "shell" to "/bin/sh", "sudo" to "ALL=(ALL) NOPASSWD:ALL")) {
            text = CloudInitEditor.patch(text, field, value)
        }
        text = CloudInitEditor.patch(text, "lock_passwd", false)
        val account = requireNotNull(CloudInitEditor.read(text))
        assertEquals("users", account.primaryGroup)
        assertEquals("users, wheel", account.groups)
        assertEquals("/bin/sh", account.shell)
        assertEquals("ALL=(ALL) NOPASSWD:ALL", account.sudo)
        assertFalse(account.locked)
        val yaml = org.yaml.snakeyaml.Yaml()
        val before = yaml.load<Map<String, Any>>(source)
        val after = yaml.load<Map<String, Any>>(text)
        for (field in listOf("packages", "runcmd", "write_files")) assertEquals(before[field], after[field])
        text = CloudInitEditor.patch(text, "sudo", "false")
        assertEquals("false", CloudInitEditor.read(text)?.sudo)
        text = CloudInitEditor.patch(text, "sudo", "")
        assertFalse(text.contains("sudo:"))
        text = CloudInitEditor.patch(text, "primary_group", "")
        assertFalse(text.contains("primary_group:"))
    }
    @Test fun acceptsScalarGroupsAndMultilineSudoRules() {
        val text = source.replace("groups: [wheel, video]", "groups: users").replace("sudo: false", "sudo: ['ALL=(ALL) ALL', 'ALL=(root) /usr/bin/id']")
        val form = requireNotNull(CloudInitEditor.read(text))
        assertEquals("users", form.groups)
        assertEquals("ALL=(ALL) ALL\nALL=(root) /usr/bin/id", form.sudo)
    }
    @Test fun updatesKeysHostAndSshAndReadsBack() {
        var text = CloudInitEditor.INITIAL
        text = CloudInitEditor.patch(text, "hostname", "my-vm")
        text = CloudInitEditor.patch(text, "ssh_authorized_keys", "ssh-ed25519 AAAA test")
        text = CloudInitEditor.patch(text, "ssh_pwauth", true)
        val form = requireNotNull(CloudInitEditor.read(text))
        assertEquals("my-vm", form.hostname)
        assertEquals("ssh-ed25519 AAAA test", form.keys)
        assertTrue(form.ssh)
        assertEquals("", CloudInitEditor.read(CloudInitEditor.patch(text, "hostname", ""))?.hostname)
    }
    @Test fun passwordPatchReplacesOnlyUserPasswordFields() {
        val text = source.replace("    groups:", "    plain_text_passwd: secret\n    passwd: oldhash\n    groups:")
        val edited = CloudInitEditor.patch(text, "hashed_passwd", "newhash")
        assertFalse(edited.contains("secret"))
        assertFalse(edited.contains("oldhash"))
        assertTrue(edited.contains("lock_passwd: false"))
        assertTrue(edited.contains("sudo: false"))
        assertEquals("newhash", CloudInitEditor.read(edited)?.hash)
    }
    @Test fun defaultOnlyAndInvalidYamlCannotBeOverwrittenByForm() {
        val text = "#cloud-config\nusers: [default]\n"
        assertNull(CloudInitEditor.read(text))
        assertThrows(IllegalArgumentException::class.java) { CloudInitEditor.patch(text, "name", "bob") }
        assertThrows(Exception::class.java) { CloudInitEditor.read("#cloud-config\nusers: [") }
    }
}
