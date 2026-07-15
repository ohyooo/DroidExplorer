package dev.droidfiles.cli

import dev.droidfiles.adb.AdbClient
import dev.droidfiles.adb.AdbLocator
import dev.droidfiles.adb.ConnectedAdbSession
import dev.droidfiles.client.ConflictPolicy
import dev.droidfiles.client.EntryType
import dev.droidfiles.privilege.PrivilegeSessionPlan
import dev.droidfiles.privilege.PrivilegeSettingsStore
import dev.droidfiles.protocol.RemotePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

fun main(args: Array<String>): Unit = runBlocking {
    val adb = AdbClient(AdbLocator().locate())
    if (args.firstOrNull() == "devices") {
        adb.devices().forEach { println("${it.serial}\t${it.state}\t${it.attributes["model"] ?: ""}") }; return@runBlocking
    }
    if (args.firstOrNull() == "relay") {
        require(args.size == 6) { usage() }
        val source = connect(adb, args[1], Path.of(args[3]), this)
        val target = connect(adb, args[2], Path.of(args[3]), this)
        source.use { from ->
            target.use { to ->
                val remote = RemotePath.of(args[4]);
                val stat = from.fileSystem.stat(remote)
                require(stat.type == EntryType.FILE) { "relay currently accepts one file" }
                from.rawTransfers.relayTo(remote, stat.size, to.rawTransfers, RemotePath.of(args[5]), UUID.randomUUID().toString()) { done -> System.err.print("\r$done/${stat.size}") }
                System.err.println()
            }
        }
        return@runBlocking
    }
    require(args.size >= 3) { usage() }
    connect(adb, args[0], Path.of(args[1]), this).use { session ->
        val fs = session.fileSystem
        when (args[2]) {
            "ls" -> fs.listDirectory(RemotePath.of(args[3])).collect { batch -> batch.entries.forEach { println("${it.type}\t${it.size}\t${it.path}") } }
            "stat" -> println(fs.stat(RemotePath.of(args[3])))
            "mkdir" -> fs.mkdir(RemotePath.of(args[3]), true)
            "rm" -> discard(fs.delete(args.drop(3).map { RemotePath.of(it) }, true))
            "hash" -> println(fs.computeHash(RemotePath.of(args[3])))
            "mv" -> fs.rename(RemotePath.of(args[3]), RemotePath.of(args[4]), ConflictPolicy.REPLACE)
            "cp" -> discard(fs.copy(listOf(RemotePath.of(args[3])), RemotePath.of(args[4]), ConflictPolicy.REPLACE))
            "push" -> {
                val local = Path.of(args[3]);
                val remote = RemotePath.of(args[4]); if (Files.isDirectory(local)) session.rawTransfers.uploadTree(local, remote, UUID.randomUUID().toString()) { done -> System.err.print("\r$done bytes") } else session.rawTransfers.upload(local, remote, UUID.randomUUID().toString()) { done -> System.err.print("\r$done/${Files.size(local)}") }; System.err.println()
            }

            "pull" -> {
                val remote = RemotePath.of(args[3]);
                val stat = fs.stat(remote); if (stat.type == EntryType.DIRECTORY) session.rawTransfers.downloadTree(remote, Path.of(args[4]), UUID.randomUUID().toString()) { done -> System.err.print("\r$done bytes") } else session.rawTransfers.download(remote, Path.of(args[4]), stat.size, UUID.randomUUID().toString()) { done -> System.err.print("\r$done/${stat.size}") }; System.err.println()
            }

            else -> error(usage())
        }
    }
}

private suspend fun connect(adb: AdbClient, serial: String, server: Path, scope: CoroutineScope): ConnectedAdbSession {
    val plan = PrivilegeSessionPlan(PrivilegeSettingsStore().load().forDevice(serial))
    plan.prepare(adb, serial)
    return ConnectedAdbSession.connect(adb, serial, server, scope, plan::wrap, plan::validate)
}

private fun usage() = "Usage: droidfiles devices | relay <source-serial> <target-serial> <server-jar> <source> <target> | <serial> <server-jar> ls|stat|mkdir|pull|push|mv|cp|rm|hash <args>"
private fun discard(@Suppress("UNUSED_PARAMETER") value: Any?): Unit = Unit
