package dev.droidfiles.cli

import dev.droidfiles.adb.*
import dev.droidfiles.privilege.*
import dev.droidfiles.protocol.RemotePath
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.time.measureTime

fun benchmarkMain(args: Array<String>): Unit = runBlocking {
    require(args.size == 5) { "Usage: <serial> <server-jar> <source> <remote> <download-target>" }
    val serial = args[0];
    val server = Path.of(args[1]);
    val source = Path.of(args[2]);
    val remote = RemotePath.of(args[3]);
    val target = Path.of(args[4])
    val adb = AdbClient(AdbLocator().locate());
    val plan = PrivilegeSessionPlan(PrivilegeSettingsStore().load().forDevice(serial)); plan.prepare(adb, serial)
    ConnectedAdbSession.connect(adb, serial, server, this, plan::wrap, plan::validate).use { session ->
        val size = Files.size(source);
        val upload = measureTime { session.rawTransfers.upload(source, remote, "bench-up-${UUID.randomUUID()}") };
        val download = measureTime { session.rawTransfers.download(remote, target, size, "bench-down-${UUID.randomUUID()}") }
        println("DROIDFILES_BENCH ${upload.inWholeNanoseconds} ${download.inWholeNanoseconds}")
    }
}

object BenchmarkEntry {
    @JvmStatic
    fun main(args: Array<String>) = benchmarkMain(args)
}
