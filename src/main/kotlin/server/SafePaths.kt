package server

import java.io.File

// Pure, testable path-safety guard for the File API (P0 hardening).
//
// Every user-supplied destination path MUST pass validateDestination():
//   1. the raw name is normalized and rejected when it contains separators or
//      ".." segments,
//   2. the resolved absolute path must stay inside the server root,
//   3. system files managed by the core can never be overwritten,
//   4. the file extension must be on the upload allowlist,
//   5. existing symlinks must resolve inside the server root (symlink escape).
object SafePaths {

    // Extensions allowed for uploads (config packs, plugins/mods jars, texts).
    val ALLOWED_EXTENSIONS = setOf("jar", "zip", "properties", "yml", "yaml", "json", "txt", "conf", "motd")

    // Core-managed files that an upload must never overwrite.
    val PROTECTED_FILES = setOf("eula.txt", "server.properties", "metadata.json")

    // Top-level folders an upload may target; anything else is rejected.
    val ALLOWED_SUBDIRS = setOf("plugins", "mods", "root")

    sealed class Validation {
        data class Ok(val file: File) : Validation()
        data class Reject(val reason: String) : Validation()
    }

    // subdir: plugins | mods | root (root => server root itself)
    // rawName: user-supplied file name (no directories allowed)
    fun validateDestination(serverRoot: File, subdir: String, rawName: String): Validation {
        val cleanSubdir = subdir.trim().lowercase()
        if (cleanSubdir !in ALLOWED_SUBDIRS) {
            return Validation.Reject("subdir must be one of $ALLOWED_SUBDIRS")
        }

        val name = rawName.trim().replace('\\', '/')
        if (name.isEmpty() || name == "." || name == "..") {
            return Validation.Reject("file name must not be empty")
        }
        if (name.contains('/') || name.contains('\\')) {
            return Validation.Reject("file name must not contain path separators")
        }
        if (name.contains("..") || name.contains('\u0000')) {
            return Validation.Reject("file name contains forbidden characters")
        }

        val root = serverRoot.absoluteFile.normalize()
        val baseDir = if (cleanSubdir == "root") root else File(root, cleanSubdir).normalize()
        if (!baseDir.path.startsWith(root.path)) {
            return Validation.Reject("subdir escapes the server root")
        }

        val dest = File(baseDir, name).normalize()
        if (!dest.path.startsWith(root.path + File.separator) && dest.path != root.path) {
            return Validation.Reject("destination escapes the server root")
        }

        val protected = PROTECTED_FILES.map { File(root, it).normalize().path }
        if (dest.path in protected) {
            return Validation.Reject("overwriting core-managed file '${dest.name}' is not allowed")
        }

        val ext = dest.extension.lowercase()
        if (ext !in ALLOWED_EXTENSIONS) {
            return Validation.Reject("file extension '.$ext' is not allowed (allowed: $ALLOWED_EXTENSIONS)")
        }

        // Symlink escape check: an existing link must resolve inside the root.
        // Uses toRealPath (follows links) instead of canonicalFile which is
        // purely lexical on some platforms.
        if (dest.exists()) {
            val real = runCatching { dest.toPath().toRealPath() }.getOrNull()
                ?: return Validation.Reject("cannot resolve destination")
            val realRoot = runCatching { root.toPath().toRealPath() }.getOrDefault(root.toPath())
            if (!real.startsWith(realRoot) || real == realRoot) {
                return Validation.Reject("destination resolves outside the server root (symlink escape)")
            }
        }

        return Validation.Ok(dest)
    }

    // P1: read guard — name must resolve inside the root; no extension
    // allowlist (reading eula.txt is legitimate) but core metadata.json stays
    // node-internal and is never exposed.
    fun validateRead(serverRoot: File, subdir: String, rawName: String): Validation {
        val cleanSubdir = subdir.trim().lowercase()
        if (cleanSubdir !in ALLOWED_SUBDIRS) {
            return Validation.Reject("subdir must be one of $ALLOWED_SUBDIRS")
        }
        val name = rawName.trim().replace('\\', '/')
        if (name.isEmpty() || name == "." || name == "..") {
            return Validation.Reject("file name must not be empty")
        }
        if (name.contains('/') || name.contains('\\') || name.contains("..") || name.contains('\u0000')) {
            return Validation.Reject("file name must not contain path separators or '..'")
        }
        val root = serverRoot.absoluteFile.normalize()
        val baseDir = if (cleanSubdir == "root") root else File(root, cleanSubdir).normalize()
        val dest = File(baseDir, name).normalize()
        if (!dest.path.startsWith(root.path + File.separator) && dest.path != root.path) {
            return Validation.Reject("path escapes the server root")
        }
        if (dest.name == "metadata.json") {
            return Validation.Reject("metadata.json is node-internal")
        }
        if (dest.exists()) {
            val real = runCatching { dest.toPath().toRealPath() }.getOrNull()
                ?: return Validation.Reject("cannot resolve path")
            val realRoot = runCatching { root.toPath().toRealPath() }.getOrDefault(root.toPath())
            if (!real.startsWith(realRoot)) {
                return Validation.Reject("path resolves outside the server root (symlink escape)")
            }
        }
        return Validation.Ok(dest)
    }

    // P1: delete guard — inside root, valid subdir, never core-managed files.
    fun validateDelete(serverRoot: File, subdir: String, rawName: String): Validation {
        val r = validateRead(serverRoot, subdir, rawName)
        if (r is Validation.Reject) return r
        val file = (r as Validation.Ok).file
        if (file.name in PROTECTED_FILES) {
            return Validation.Reject("deleting core-managed file '${file.name}' is not allowed")
        }
        return Validation.Ok(file)
    }
    // Lists a folder safely; the folder itself is resolved against the root and
    // rejected when it escapes (defends the GET listing path too).
    fun validateListDir(serverRoot: File, subdir: String): Validation {
        val cleanSubdir = subdir.trim().lowercase()
        if (cleanSubdir !in ALLOWED_SUBDIRS) {
            return Validation.Reject("subdir must be one of $ALLOWED_SUBDIRS")
        }
        val root = serverRoot.absoluteFile.normalize()
        val dir = if (cleanSubdir == "root") root else File(root, cleanSubdir).normalize()
        if (!dir.path.startsWith(root.path)) {
            return Validation.Reject("subdir escapes the server root")
        }
        return Validation.Ok(dir)
    }
}
