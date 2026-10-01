/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.core

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.*
import org.yaml.snakeyaml.representer.Representer
import java.io.StringReader
import java.io.StringWriter

/** Patch only the requested field in the syntax tree, retaining unrelated YAML nodes. */
internal object CloudInitEditor {
    const val INITIAL = "#cloud-config\nusers:\n  - name: droid\n    shell: /bin/bash\n    sudo: ['ALL=(ALL) ALL']\n    lock_passwd: true\nssh_pwauth: false\ndisable_root: true\nchpasswd:\n  expire: false\n"
    data class Account(val username: String, val hash: String, val hostname: String, val keys: String, val ssh: Boolean, val primaryGroup: String, val groups: String, val shell: String, val sudo: String, val locked: Boolean)
    private fun parser(): Yaml {
        val loader = LoaderOptions().apply { isProcessComments = true; isAllowDuplicateKeys = false; maxAliasesForCollections = 20; codePointLimit = 65536 }
        val dumper = DumperOptions().apply { isProcessComments = true; defaultFlowStyle = DumperOptions.FlowStyle.BLOCK }
        return Yaml(SafeConstructor(loader), Representer(dumper), dumper, loader)
    }
    private fun MappingNode.field(key: String) = value.firstOrNull { (it.keyNode as? ScalarNode)?.value == key }?.valueNode
    private fun MappingNode.put(key: String, node: Node?) {
        val index = value.indexOfFirst { (it.keyNode as? ScalarNode)?.value == key }
        if (node == null) { if (index >= 0) value.removeAt(index); return }
        val tuple = NodeTuple(ScalarNode(Tag.STR, key, null, null, DumperOptions.ScalarStyle.PLAIN), node)
        if (index < 0) value.add(tuple) else value[index] = tuple
    }
    private fun account(root: MappingNode): MappingNode? {
        val users = root.field("users") as? SequenceNode ?: return null
        return users.value.filterIsInstance<MappingNode>().firstOrNull { it.field("name") is ScalarNode }
    }
    fun read(text: String): Account? {
        CloudInit.custom(text, "validate")
        val root = parser().compose(StringReader(text)) as MappingNode
        val user = account(root) ?: return null
        fun Node?.string() = (this as? ScalarNode)?.value ?: ""
        val keys = user.field("ssh_authorized_keys")
        if (keys != null && (keys !is SequenceNode || keys.value.any { it !is ScalarNode })) return null
        fun Node?.lines(separator: String): String = when (this) {
            null -> ""
            is ScalarNode -> if (tag == Tag.NULL) "" else value
            is SequenceNode -> value.joinToString(separator) { (it as ScalarNode).value }
            else -> ""
        }
        for (field in listOf("groups", "sudo")) {
            val value = user.field(field)
            if (value != null && value !is ScalarNode &&
                (value !is SequenceNode || value.value.any { it !is ScalarNode })) return null
        }
        return Account(user.field("name").string(), (user.field("hashed_passwd") ?: user.field("passwd")).string(),
            root.field("hostname").string(), (keys as? SequenceNode)?.value?.joinToString("\n") { it.string() } ?: "",
            root.field("ssh_pwauth").string().lowercase() in listOf("true", "yes", "on"),
            user.field("primary_group").string(), user.field("groups").lines(", "),
            user.field("shell").string(), user.field("sudo").lines("\n"),
            user.field("lock_passwd") == null || user.field("lock_passwd").string().lowercase() in listOf("true", "yes", "on"))
    }
    fun patch(text: String, field: String, value: Any): String {
        CloudInit.custom(text, "validate")
        val yaml = parser()
        val root = yaml.compose(StringReader(text)) as MappingNode
        val original = requireNotNull(account(root)) { "No named user is available; edit users in YAML first" }
        // Detach the edited user from aliases shared with other users/custom fields.
        val copy = StringWriter().also { yaml.serialize(original, it) }
        val user = yaml.compose(StringReader(copy.toString())) as MappingNode
        val users = root.field("users") as SequenceNode
        users.value[users.value.indexOf(original)] = user
        fun node(v: Any) = yaml.represent(v)
        when (field) {
            "name" -> user.put("name", node(value))
            "primary_group", "shell" -> user.put(field, if (value == "") null else node(value))
            "groups" -> {
                val groups = (value as String).split(',', '\n').map(String::trim).filter(String::isNotEmpty)
                user.put(field, if (groups.isEmpty()) null else node(groups))
            }
            "sudo" -> {
                val rules = (value as String).lines().map(String::trim).filter(String::isNotEmpty)
                user.put(field, when {
                    rules.isEmpty() -> null
                    rules == listOf("false") -> node(false)
                    else -> node(rules)
                })
            }
            "lock_passwd" -> user.put(field, node(value))
            "hostname" -> root.put("hostname", if (value == "") null else node(value))
            "ssh_pwauth" -> root.put(field, node(value))
            "ssh_authorized_keys" -> user.put(field, node((value as String).lines().map(String::trim).filter(String::isNotEmpty)))
            "hashed_passwd" -> {
                user.put("hashed_passwd", node(value))
                user.put("lock_passwd", node(false))
                user.put("passwd", null)
                user.put("plain_text_passwd", null)
            }
            else -> error("Unknown account field")
        }
        val output = StringWriter()
        yaml.serialize(root, output)
        val result = output.toString()
        return if (result.startsWith("#cloud-config\n")) result else "#cloud-config\n$result"
    }
}
