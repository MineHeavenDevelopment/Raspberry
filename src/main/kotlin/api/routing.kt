package api

import config.AppConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readRemaining
import managers.CoreRuntime
import kotlinx.io.asInputStream
import org.json.JSONArray
import org.json.JSONObject
import server.SafePaths
import utils.logger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture

private suspend fun respondJson(call: ApplicationCall, json: JSONObject, status: HttpStatusCode = HttpStatusCode.OK) {
    call.respondText(json.toString(), ContentType.Application.Json, status)
}

// P0: canonical X-Core-Token guard; legacy X-Admin-Token is an accepted alias.
private suspend fun ApplicationCall.authorized(config: AppConfig): Boolean {
    val legacy = request.headers["X-Admin-Token"]
    val token = request.headers["X-Core-Token"] ?: legacy
    if (legacy != null) CoreToken.warnDeprecatedHeaderOnce()
    if (CoreToken.matches(token)) return true
    respondText(
        JSONObject().put("error", "unauthorized: missing or invalid X-Core-Token").toString(),
        ContentType.Application.Json,
        HttpStatusCode.Unauthorized
    )
    return false
}

private fun sanitizeId(raw: String): String = raw.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")

private fun serversDirUsableMb(): Long = runCatching {
    java.io.File("./servers").usableSpace / (1024L * 1024L)
}.getOrDefault(0L)

private suspend fun rateLimited(call: ApplicationCall, limiter: RateLimiter): Boolean {
    val retry = limiter.allow(CoreRateLimits.clientIp(call))
    if (retry > 0) {
        CoreRateLimits.respondRateLimited(call, retry)
        return true
    }
    return false
}



// Admin HTTP API of the core node (Ktor on [api].http_port, default 8081).
// All /api/v1/* routes require header X-Admin-Token == [api].admin_token.
fun Application.module() {
    install(ContentNegotiation) {
        json()
    }

    CoreRuntime.start()

    val config = CoreRuntime.config
    val registry = CoreRuntime.registry

    routing {
        get("/") {
            call.respondText("Raspberry Core â€” node '${config.node.name}' is running")
        }

        // P0: liveness (process is up) — no auth.
        get("/v1/health/live") {
            respondJson(call, JSONObject().put("live", true).put("node", config.node.name))
        }

        // P0: readiness (dependencies + capacity) — no auth, semantic fields.
        get("/v1/health/ready") {
            val redisUp = runCatching { CoreRuntime.redisAvailable() }.getOrDefault(false)
            val workspaceWritable = runCatching {
                val probe = File("./state/.ready-probe")
                probe.writeText("ok"); probe.delete(); true
            }.getOrDefault(false)
            val incusOk = if (config.runtime.mode.trim().lowercase() == "incus") {
                runCatching { incus.IncusCli().available() }.getOrDefault(false)
            } else true
            // P0 resource-aware scheduling support: expose real node capacity.
            val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            val totalMemMb = osBean.totalMemorySize / (1024L * 1024L)
            val freeMemMb = osBean.freeMemorySize / (1024L * 1024L)
            val disk = serversDirUsableMb()
            respondJson(call, JSONObject().apply {
                put("ready", redisUp && workspaceWritable && incusOk)
                put("redis", redisUp)
                put("workspace", workspaceWritable)
                put("incus", incusOk)
                put("runtime_mode", config.runtime.mode)
                put("active_servers", registry.activeCount())
                put("max_servers", config.node.maxServers)
                put("memory_total_mb", totalMemMb)
                put("memory_free_mb", freeMemMb)
                put("disk_usable_mb", disk)
                put("cpu_load", osBean.systemCpuLoad)
            })
        }

        // Kept for backward compatibility; status reflects readiness.
        get("/healthz") {
            val redisUp = runCatching { CoreRuntime.redisAvailable() }.getOrDefault(false)
            respondJson(call, JSONObject().apply {
                put("status", if (redisUp) "ok" else "degraded")
                put("node", config.node.name)
                put("public_host", config.node.publicHost)
                put("active_servers", registry.activeCount())
                put("max_servers", config.node.maxServers)
            })
        }
        route("/api/v1") {

            // Accepts create_server or power_server job bodies (same JSON as the Redis contract)
            post("/jobs") {
                if (!call.authorized(config)) return@post
                if (rateLimited(call, CoreRateLimits.jobs)) return@post
                val body = try {
                    JSONObject(call.receiveText())
                } catch (e: Exception) {
                    return@post respondJson(call, JSONObject().put("error", "invalid JSON body"), HttpStatusCode.BadRequest)
                }

                when (body.optString("type")) {
                    "create_server" -> {
                        val requestId = body.optString("request_id").trim()
                        if (requestId.isEmpty()) {
                            return@post respondJson(call, JSONObject().put("error", "request_id is required"), HttpStatusCode.BadRequest)
                        }
                        val key = sanitizeId(requestId)
                        // P0 idempotency: any known state for this request_id is
                        // returned as 200 instead of creating a duplicate server.
                        val existing = registry.get(key) ?: run {
                            val metaFile = File("./servers/$key/metadata.json")
                            if (!metaFile.exists()) null else runCatching {
                                val meta = server.ServerMeta.fromJson(JSONObject(metaFile.readText()))
                                registry.registerProvisioning(meta, File("./servers/$key")).also { it.status = "stopped" }
                            }.getOrNull()
                        }
                        if (existing != null) {
                            logger("operation=create_server server_id=$key result=idempotent_hit status=${existing.status}", error = false)
                            return@post respondJson(call, JSONObject().apply {
                                put("status", existing.status)
                                put("request_id", requestId)
                                put("ip", config.node.publicHost)
                                put("port", existing.port)
                            })
                        }
                        if (registry.activeCount() >= config.node.maxServers) {
                            return@post respondJson(
                                call,
                                JSONObject().put("error", "Node capacity reached: max_servers=${config.node.maxServers}"),
                                HttpStatusCode.Conflict
                            )
                        }

                        val job = job.JobParser.parse(body.toString()) as? job.CoreJob.CreateServer
                            ?: return@post respondJson(call, JSONObject().put("error", "invalid create_server payload"), HttpStatusCode.BadRequest)

                        // Reuse the same pipeline as Redis jobs so events stay identical
                        CompletableFuture.runAsync { CoreRuntime.provisioner.handleCreate(job) }
                        respondJson(call, JSONObject().apply {
                            put("status", "accepted")
                            put("request_id", requestId)
                        }, HttpStatusCode.Accepted)
                    }
                    "power_server" -> {
                        val serverId = (body.optString("server_id") + body.optString("request_id")).trim()
                        val action = body.optString("action", "stop").trim().lowercase()
                        if (serverId.isEmpty()) {
                            return@post respondJson(call, JSONObject().put("error", "server_id is required"), HttpStatusCode.BadRequest)
                        }
                        if (action !in setOf("start", "stop", "restart")) {
                            return@post respondJson(call, JSONObject().put("error", "action must be start|stop|restart"), HttpStatusCode.BadRequest)
                        }
                        val key = sanitizeId(serverId)
                        if (registry.get(key) == null && !File("./servers/$key/metadata.json").exists()) {
                            return@post respondJson(call, JSONObject().put("error", "unknown server_id on this node"), HttpStatusCode.NotFound)
                        }
                        CompletableFuture.runAsync { CoreRuntime.provisioner.handlePower(job.CoreJob.PowerServer(serverId, action)) }
                        respondJson(call, JSONObject().apply {
                            put("status", "accepted")
                            put("server_id", serverId)
                            put("action", action)
                        }, HttpStatusCode.Accepted)
                    }
                    else -> respondJson(call, JSONObject().put("error", "type must be create_server or power_server"), HttpStatusCode.BadRequest)
                }
            }

            get("/servers") {
                if (!call.authorized(config)) return@get
                respondJson(call, CoreRuntime.statusSnapshot())
            }

            get("/servers/{request_id}") {
                if (!call.authorized(config)) return@get
                val id = call.parameters["request_id"] ?: return@get respondJson(
                    call, JSONObject().put("error", "missing request_id"), HttpStatusCode.BadRequest
                )
                val key = sanitizeId(id)
                val rs = registry.get(key) ?: run {
                    // Known workspace but not yet registered in this runtime (e.g. right after restart)
                    val metaFile = File("./servers/$key/metadata.json")
                    if (!metaFile.exists()) return@run null
                    try {
                        val meta = server.ServerMeta.fromJson(JSONObject(metaFile.readText()))
                        registry.registerProvisioning(meta, File("./servers/$key")).also { it.status = "stopped" }
                    } catch (e: Exception) {
                        null
                    }
                }
                if (rs == null) {
                    return@get respondJson(call, JSONObject().put("error", "unknown request_id"), HttpStatusCode.NotFound)
                }
                respondJson(call, rs.statusJson(config.node.publicHost))
            }

            get("/servers/{request_id}/console") {
                if (!call.authorized(config)) return@get
                val id = call.parameters["request_id"] ?: return@get respondJson(
                    call, JSONObject().put("error", "missing request_id"), HttpStatusCode.BadRequest
                )
                val lines = call.request.queryParameters["lines"]?.toIntOrNull() ?: 100
                val console = registry.get(sanitizeId(id))?.console
                if (console == null) {
                    return@get respondJson(call, JSONObject().put("error", "no console available for $id"), HttpStatusCode.NotFound)
                }
                respondJson(call, JSONObject().put("lines", JSONArray(console.snapshot(lines))))
            }

            post("/servers/{request_id}/console") {
                if (!call.authorized(config)) return@post
                if (rateLimited(call, CoreRateLimits.console)) return@post
                val id = call.parameters["request_id"] ?: return@post respondJson(
                    call, JSONObject().put("error", "missing request_id"), HttpStatusCode.BadRequest
                )
                val body = try {
                    JSONObject(call.receiveText())
                } catch (e: Exception) {
                    return@post respondJson(call, JSONObject().put("error", "invalid JSON body"), HttpStatusCode.BadRequest)
                }
                val command = body.optString("command").trim()
                if (command.isEmpty()) {
                    return@post respondJson(call, JSONObject().put("error", "command is required"), HttpStatusCode.BadRequest)
                }
                val process = registry.get(sanitizeId(id))?.process
                if (process == null || !process.isAlive) {
                    return@post respondJson(call, JSONObject().put("error", "server is not running"), HttpStatusCode.Conflict)
                }
                try {
                    process.outputStream.apply {
                        write((command + "\n").toByteArray())
                        flush()
                    }
                    respondJson(call, JSONObject().put("ok", true).put("command", command))
                } catch (e: Exception) {
                    respondJson(call, JSONObject().put("error", "failed to send command: ${e.message}"), HttpStatusCode.InternalServerError)
                }
            }

            post("/servers/{request_id}/power") {
                if (!call.authorized(config)) return@post
                val id = call.parameters["request_id"] ?: return@post respondJson(
                    call, JSONObject().put("error", "missing request_id"), HttpStatusCode.BadRequest
                )
                val body = try {
                    JSONObject(call.receiveText())
                } catch (e: Exception) {
                    JSONObject().put("action", call.request.queryParameters["action"] ?: "")
                }
                val action = body.optString("action").trim().lowercase()
                if (action !in setOf("start", "stop", "restart")) {
                    return@post respondJson(call, JSONObject().put("error", "action must be start|stop|restart"), HttpStatusCode.BadRequest)
                }
                val key = sanitizeId(id)
                if (registry.get(key) == null && !File("./servers/$key/metadata.json").exists()) {
                    return@post respondJson(call, JSONObject().put("error", "unknown server_id on this node"), HttpStatusCode.NotFound)
                }
                CompletableFuture.runAsync { CoreRuntime.provisioner.handlePower(job.CoreJob.PowerServer(id, action)) }
                respondJson(call, JSONObject().apply {
                    put("status", "accepted")
                    put("server_id", id)
                    put("action", action)
                }, HttpStatusCode.Accepted)
            }

            // ---- P1 file download + delete ----

            get("/servers/{request_id}/files/download") {
                if (!call.authorized(config)) return@get
                val id = sanitizeId(call.parameters["request_id"] ?: "")
                if (id.isEmpty()) {
                    return@get respondJson(call, JSONObject().put("error", "missing request_id"), HttpStatusCode.BadRequest)
                }
                val root = File("./servers/$id")
                if (!File(root, "metadata.json").exists()) {
                    return@get respondJson(call, JSONObject().put("error", "unknown server_id on this node"), HttpStatusCode.NotFound)
                }
                val subdir = call.request.queryParameters["subdir"] ?: "plugins"
                val name = call.request.queryParameters["name"] ?: ""
                when (val check = SafePaths.validateRead(root, subdir, name)) {
                    is SafePaths.Validation.Reject ->
                        respondJson(call, JSONObject().put("error", check.reason), HttpStatusCode.BadRequest)
                    is SafePaths.Validation.Ok -> {
                        val file = check.file
                        if (!file.exists() || !file.isFile) {
                            respondJson(call, JSONObject().put("error", "file not found"), HttpStatusCode.NotFound)
                        } else if (file.length() > 200L * 1024L * 1024L) {
                            respondJson(call, JSONObject().put("error", "file too large"), HttpStatusCode.PayloadTooLarge)
                        } else {
                            call.response.headers.append("Content-Disposition", "attachment; filename=\"${file.name}\"")
                            call.respondBytes(file.readBytes(), ContentType.Application.OctetStream)
                        }
                    }
                }
            }

            delete("/servers/{request_id}/files") {
                if (!call.authorized(config)) return@delete
                val id = sanitizeId(call.parameters["request_id"] ?: "")
                if (id.isEmpty()) {
                    return@delete respondJson(call, JSONObject().put("error", "missing request_id"), HttpStatusCode.BadRequest)
                }
                val root = File("./servers/$id")
                if (!File(root, "metadata.json").exists()) {
                    return@delete respondJson(call, JSONObject().put("error", "unknown server_id on this node"), HttpStatusCode.NotFound)
                }
                val subdir = call.request.queryParameters["subdir"] ?: "plugins"
                val name = call.request.queryParameters["name"] ?: ""
                when (val check = SafePaths.validateDelete(root, subdir, name)) {
                    is SafePaths.Validation.Reject ->
                        respondJson(call, JSONObject().put("error", check.reason), HttpStatusCode.BadRequest)
                    is SafePaths.Validation.Ok -> {
                        val file = check.file
                        if (!file.exists() || !file.isFile) {
                            respondJson(call, JSONObject().put("error", "file not found"), HttpStatusCode.NotFound)
                        } else if (file.delete()) {
                            logger("operation=file_delete server_id=$id result=success file=${file.name} subdir=${subdir.trim().lowercase()}", error = false)
                            respondJson(call, JSONObject().put("ok", true))
                        } else {
                            respondJson(call, JSONObject().put("error", "could not delete file"), HttpStatusCode.InternalServerError)
                        }
                    }
                }
            }

            // ---- P0 File API (safe upload + listing) ----

            get("/servers/{request_id}/files") {
                if (!call.authorized(config)) return@get
                val id = sanitizeId(call.parameters["request_id"] ?: "")
                if (id.isEmpty()) {
                    return@get respondJson(call, JSONObject().put("error", "missing request_id"), HttpStatusCode.BadRequest)
                }
                val root = File("./servers/$id")
                if (!File(root, "metadata.json").exists()) {
                    return@get respondJson(call, JSONObject().put("error", "unknown server_id on this node"), HttpStatusCode.NotFound)
                }
                val subdir = call.request.queryParameters["subdir"] ?: "plugins"
                when (val check = SafePaths.validateListDir(root, subdir)) {
                    is SafePaths.Validation.Reject ->
                        respondJson(call, JSONObject().put("error", check.reason), HttpStatusCode.BadRequest)
                    is SafePaths.Validation.Ok -> {
                        val dir = check.file
                        val arr = JSONArray()
                        if (dir.exists()) {
                            for (f in dir.listFiles()?.sortedBy { it.name } ?: emptyList()) {
                                if (!f.isFile) continue
                                arr.put(JSONObject().apply {
                                    put("name", f.name)
                                    put("size", f.length())
                                    put("modified_at", f.lastModified())
                                })
                            }
                        }
                        respondJson(call, JSONObject().put("subdir", subdir.trim().lowercase()).put("files", arr))
                    }
                }
            }

            post("/servers/{request_id}/files") {
                if (!call.authorized(config)) return@post
                if (rateLimited(call, CoreRateLimits.files)) return@post
                val id = sanitizeId(call.parameters["request_id"] ?: "")
                if (id.isEmpty()) {
                    return@post respondJson(call, JSONObject().put("error", "missing request_id"), HttpStatusCode.BadRequest)
                }
                val root = File("./servers/$id")
                if (!File(root, "metadata.json").exists()) {
                    return@post respondJson(call, JSONObject().put("error", "unknown server_id on this node"), HttpStatusCode.NotFound)
                }

                var subdir = "plugins"
                var rawName = ""
                var fileBytes: ByteArray? = null
                try {
                    val multipart = call.receiveMultipart()
                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> when (part.name) {
                                "subdir" -> subdir = part.value
                                "path" -> rawName = part.value
                            }
                            is PartData.BinaryChannelItem -> {
                                if (part.name == "file") fileBytes = part.provider().toInputStream(kotlinx.coroutines.Job()).readBytes()
                            }
                            is PartData.FileItem -> {
                                if (part.name == "file") fileBytes = part.provider().toInputStream(kotlinx.coroutines.Job()).readBytes()
                            }
                            else -> {}
                        }
                        part.dispose()
                    }
                } catch (e: Exception) {
                    return@post respondJson(call, JSONObject().put("error", "invalid multipart body: ${e.message}"), HttpStatusCode.BadRequest)
                }

                if (rawName.isBlank()) rawName = "upload.jar"
                when (val check = SafePaths.validateDestination(root, subdir, rawName)) {
                    is SafePaths.Validation.Reject ->
                        return@post respondJson(call, JSONObject().put("error", check.reason), HttpStatusCode.BadRequest)
                    is SafePaths.Validation.Ok -> {
                        val bytes = fileBytes
                            ?: return@post respondJson(call, JSONObject().put("error", "multipart field 'file' is required"), HttpStatusCode.BadRequest)
                        val dest = check.file
                        val maxBytes = config.api.maxUploadMb.toLong() * 1024L * 1024L
                        val tmp = File(dest.parentFile, dest.name + ".upload-" + System.currentTimeMillis() + ".tmp")
                        try {
                            dest.parentFile?.mkdirs()
                            var written = 0L
                            if (bytes.size > maxBytes) throw IllegalStateException("upload exceeds max_upload_mb=" + config.api.maxUploadMb)
                            tmp.writeBytes(bytes)
                            written = bytes.size.toLong()
                                                        try {
                                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
                            } catch (e: Exception) {
                                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            }
                            logger("operation=file_upload server_id=$id result=success file=" + dest.name + " size=$written subdir=" + subdir.trim().lowercase(), error = false)
                            respondJson(call, JSONObject().apply {
                                put("ok", true)
                                put("file", dest.name)
                                put("size", written)
                                put("subdir", subdir.trim().lowercase())
                            })
                        } catch (e: Exception) {
                            tmp.delete()
                            respondJson(call, JSONObject().put("error", "upload failed: ${e.message}"), HttpStatusCode.BadRequest)
                        }
                    }
                }
            }
        }
    }
}