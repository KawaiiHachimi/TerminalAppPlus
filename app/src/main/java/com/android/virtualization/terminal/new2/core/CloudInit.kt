/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.core

import com.android.virtualization.terminal.AppStrings
import com.android.virtualization.terminal.R
import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.security.SecureRandom
import org.apache.commons.codec.digest.Sha2Crypt

internal data class CloudInitConfig(val username: String, val passwordHash: String,
    val hostname: String, val publicKeys: List<String>, val sshPassword: Boolean, val instanceId: String)

internal object CloudInit {
    private val gson = Gson()
    fun config(username: String, password: String, hostname: String, keys: String, sshPassword: Boolean, id: String, existingHash: String = ""): CloudInitConfig {
        require(username.matches(Regex("[a-z_][a-z0-9_-]{0,31}"))) { AppStrings.get(R.string.plus_invalid_username) }
        require(hostname.isBlank() || (hostname.length <= 63 && hostname.matches(Regex("[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?")))) { AppStrings.get(R.string.plus_invalid_hostname) }
        val publicKeys = keys.lines().map(String::trim).filter(String::isNotEmpty)
        require(publicKeys.all { it.matches(Regex("(?:ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(?:256|384|521)) [A-Za-z0-9+/]+={0,3}(?: .*)?")) }) { AppStrings.get(R.string.plus_invalid_ssh_key) }
        require(password.isNotEmpty() || existingHash.isNotEmpty() || publicKeys.isNotEmpty()) { AppStrings.get(R.string.plus_credentials_required) }
        require(password.length <= 1024 && keys.length <= 16384) { AppStrings.get(R.string.plus_credentials_too_long) }
        require(!sshPassword || password.isNotEmpty() || existingHash.isNotEmpty()) { AppStrings.get(R.string.plus_ssh_password_required) }
        val alphabet = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val random = SecureRandom()
        val salt = (1..16).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        val hash = if (password.isEmpty()) existingHash else Sha2Crypt.sha512Crypt(password.toByteArray(Charsets.UTF_8), "\$6\$rounds=10000\$$salt\$")
        return CloudInitConfig(username, hash, hostname.trim(), publicKeys, sshPassword, "terminal-plus-$id")
    }
    fun documents(config: CloudInitConfig): Pair<String, String> {
        val user = linkedMapOf<String, Any>("name" to config.username, "shell" to "/bin/bash", "lock_passwd" to config.passwordHash.isEmpty())
        if (config.username != "root") user["sudo"] = listOf("ALL=(ALL) ALL")
        if (config.passwordHash.isNotEmpty()) user["hashed_passwd"] = config.passwordHash
        if (config.publicKeys.isNotEmpty()) user["ssh_authorized_keys"] = config.publicKeys
        val data = linkedMapOf<String, Any>("users" to listOf(user), "ssh_pwauth" to config.sshPassword,
            "disable_root" to (config.username != "root"), "chpasswd" to mapOf("expire" to false))
        if (config.hostname.isNotEmpty()) { data["hostname"] = config.hostname; data["manage_etc_hosts"] = true }
        val meta = linkedMapOf("instance-id" to config.instanceId)
        if (config.hostname.isNotEmpty()) meta["local-hostname"] = config.hostname
        return "#cloud-config\n${gson.toJson(data)}\n" to "${gson.toJson(meta)}\n"
    }
    fun fill(template: ByteArray, layout: String, userData: String, metadata: String): ByteArray {
        val result = template.copyOf()
        val slots = JsonParser.parseString(layout).asJsonObject
        for ((name, text) in listOf("user-data" to userData, "meta-data" to metadata)) {
            val slot = slots.getAsJsonObject(name)
            val start = slot.get("offset").asInt; val length = slot.get("length").asInt
            val content = (text + "#").toByteArray(Charsets.UTF_8)
            require(start >= 0 && length > 0 && start.toLong() + length <= result.size && content.size < length) { AppStrings.get(R.string.plus_cloud_config_too_large) }
            result.fill(32, start, start + length)
            content.copyInto(result, start); result[start + length - 1] = 10
        }
        return result
    }
    fun file(directory: File) = File(directory, "cloud-init.iso")
    fun read(context: Context, directory: File): CloudInitConfig? {
        if (!file(directory).exists()) return null
        val bytes = file(directory).readBytes()
        val layout = context.assets.open("cloud-init/layout.json").bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        fun document(name: String): com.google.gson.JsonObject {
            val slot = layout.getAsJsonObject(name)
            return JsonParser.parseString(String(bytes, slot.get("offset").asInt, slot.get("length").asInt, Charsets.UTF_8)
                .removePrefix("#cloud-config\n").substringBefore("\n#").trim()).asJsonObject
        }
        val data = document("user-data"); val meta = document("meta-data")
        val user = data.getAsJsonArray("users")[0].asJsonObject
        return CloudInitConfig(user.get("name").asString, user.get("hashed_passwd")?.asString ?: "",
            data.get("hostname")?.asString ?: "", user.getAsJsonArray("ssh_authorized_keys")?.map { it.asString } ?: emptyList(),
            data.get("ssh_pwauth").asBoolean, meta.get("instance-id").asString)
    }
    fun locked(directory: File) = File(directory, "cloud-init.booted").exists() || File(directory, "vm_config.last-good.json").exists()
    fun save(context: Context, directory: File, config: CloudInitConfig?) {
        check(!locked(directory)) { AppStrings.get(R.string.plus_cloud_config_locked) }
        if (config == null) { check(!file(directory).exists() || file(directory).delete()); return }
        val (user, meta) = documents(config)
        val bytes = context.assets.open("cloud-init/template.iso").use { template ->
            val layout = context.assets.open("cloud-init/layout.json").bufferedReader().use { it.readText() }
            fill(template.readBytes(), layout, user, meta)
        }
        val atomic = AtomicFile(file(directory)); val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) } catch (e: Exception) { atomic.failWrite(output); throw e }
    }
}
