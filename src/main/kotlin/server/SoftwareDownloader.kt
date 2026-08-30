package server

import utils.logger
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Semaphore
import org.json.JSONObject

// Resolves and downloads server jars per (software, version) from official APIs.
// Cache layout: {versions_dir}/{software}/{version}.jar â€” an existing cache file
// is never downloaded again. Forge returns the INSTALLER jar; ServerProvisioner
// runs `--installServer` once and then uses the generated run script.
class SoftwareDownloader(
    versionsDir: File,
    private val autoDownload: Boolean,
    maxConcurrent: Int
) {
    private val versionsDir: File = versionsDir.apply { mkdirs() }
    private val downloadSlots = Semaphore(maxConcurrent.coerceAtLeast(1))

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    val supportedSoftware = setOf("paper", "purpur", "vanilla", "fabric", "forge")

    // Returns the cached jar (server jar, or forge installer for forge).
    @Throws(Exception::class)
    fun resolveJar(software: String, version: String): File {
        val soft = software.lowercase().trim()
        if (soft !in supportedSoftware) {
            throw IllegalArgumentException("Unsupported software '$software' (supported: $supportedSoftware)")
        }
        val dir = File(versionsDir, soft).apply { mkdirs() }
        val jar = File(dir, "$version.jar")
        if (jar.exists() && jar.length() > 0) {
            logger("Using cached jar for $soft $version -> ${jar.path}", error = false)
            return jar
        }
        if (!autoDownload) {
            throw IllegalStateException("auto_download=false and no cached jar for $soft $version")
        }

        downloadSlots.acquire()
        try {
            if (jar.exists() && jar.length() > 0) return jar // someone else finished meanwhile
            val url = resolveUrl(soft, version)
            logger("Downloading $soft $version from $url ...", error = false)
            downloadTo(url, jar)
            logger("Downloaded $soft $version (${jar.length() / 1024 / 1024} MB)", error = false)
            return jar
        } finally {
            downloadSlots.release()
        }
    }

    // ---- per-software URL resolution ----

    private fun resolveUrl(software: String, version: String): String = when (software) {
        "paper" -> paperUrl(version)
        "purpur" -> purpurUrl(version)
        "vanilla" -> vanillaUrl(version)
        "fabric" -> fabricUrl(version)
        "forge" -> forgeInstallerUrl(version)
        else -> throw IllegalArgumentException("Unsupported software '$software'")
    }

    // PaperMC deprecated the v2 API (HTTP 410) in favor of the Fill v3 API.
    private fun paperUrl(version: String): String {
        val fill = runCatching { paperUrlFillV3(version) }
        if (fill.isSuccess) return fill.getOrThrow()
        val root = getJson("https://api.papermc.io/v2/projects/paper/versions/$version/builds")
        val builds = root.optJSONArray("builds")
            ?: throw IllegalArgumentException("Paper version $version not found on PaperMC API")
        if (builds.length() == 0) throw IllegalArgumentException("Paper version $version has no builds yet")
        val latest = builds.getJSONObject(builds.length() - 1)
        val build = latest.getInt("build")
        val name = latest.getJSONObject("downloads").getJSONObject("application").getString("name")
        return "https://api.papermc.io/v2/projects/paper/versions/$version/builds/$build/downloads/$name"
    }

    private fun paperUrlFillV3(version: String): String {
        val root = getJson("https://fill.papermc.io/v3/projects/paper/versions/$version/builds/latest")
        val url = root.optJSONObject("downloads")
            ?.optJSONObject("server:default")
            ?.optString("url")
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Fill v3 response for Paper $version is malformed")
        return url
    }

    private fun purpurUrl(version: String): String {
        val meta = getJson("https://api.purpurmc.org/v2/purpur/$version")
        val latest = meta.optJSONObject("builds")?.optString("latest")
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Purpur version $version not found on Purpur API")
        return "https://api.purpurmc.org/v2/purpur/$version/$latest/download"
    }

    private fun vanillaUrl(version: String): String {
        val manifest = getJson("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
        val versions = manifest.optJSONArray("versions")
            ?: throw IllegalStateException("Mojang version manifest is malformed")
        for (i in 0 until versions.length()) {
            val v = versions.optJSONObject(i) ?: continue
            if (v.optString("id") == version) {
                val metaUrl = v.optString("url")
                if (metaUrl.isBlank()) break
                val meta = getJson(metaUrl)
                val url = meta.optJSONObject("downloads")?.optJSONObject("server")?.optString("url")
                return url?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Version $version has no server jar (client-only release?)")
            }
        }
        throw IllegalArgumentException("Vanilla version $version not found in Mojang manifest")
    }

    private fun fabricUrl(version: String): String {
        val loader = latestStableVersion("https://meta.fabricmc.net/v2/versions/loader")
            ?: throw IllegalStateException("FabricMC meta returned no loader versions")
        val installer = latestStableVersion("https://meta.fabricmc.net/v2/versions/installer")
            ?: throw IllegalStateException("FabricMC meta returned no installer versions")
        return "https://meta.fabricmc.net/v2/versions/loader/$version/$loader/$installer/server/jar"
    }

    private fun latestStableVersion(url: String): String? {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IllegalStateException("HTTP ${response.statusCode()} while fetching $url")
        }
        val arr = org.json.JSONArray(response.body())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val v = o.optString("version")
            if (v.isNotBlank() && o.optBoolean("stable", true)) return v
        }
        return null
    }

    private fun forgeInstallerUrl(version: String): String {
        val promos = getJson("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json")
            .optJSONObject("promos") ?: throw IllegalStateException("Forge promotions_slim.json malformed")
        val build = promos.optString("$version-latest").takeIf { it.isNotBlank() && it != "null" }
            ?: promos.optString("$version-recommended").takeIf { it.isNotBlank() && it != "null" }
            ?: throw IllegalArgumentException("Forge version $version not found in promotions_slim.json")
        return "https://maven.minecraftforge.net/net/minecraftforge/forge/$version-$build/forge-$version-$build-installer.jar"
    }

    // ---- helpers ----

    private fun getJson(url: String): JSONObject {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IllegalStateException("HTTP ${response.statusCode()} while fetching $url")
        }
        return JSONObject(response.body())
    }

    private fun downloadTo(url: String, target: File) {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(20))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { input ->
            if (response.statusCode() != 200) {
                throw IllegalStateException("HTTP ${response.statusCode()} while downloading $url")
            }
            val tmp = File(target.parentFile, "${target.name}.part")
            tmp.outputStream().use { output -> input.copyTo(output) }
            if (tmp.length() <= 0) throw IllegalStateException("Downloaded empty file from $url")
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }
}