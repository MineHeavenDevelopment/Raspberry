package utils

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun logger (message: String, level: String = "INFO", error: Boolean) {

    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    val timestamp = LocalDateTime.now().format(formatter)
    // ':' is illegal in Windows file names -> only sanitize the file name (not
    // the logged timestamp) on Windows; other platforms keep the old name.
    val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
    val fileName = if (isWindows) timestamp.replace(':', '-') else timestamp
    val logFile = createDirectory("./logs", "$fileName.log")
    val logEntry = "[$timestamp] [$level] $message\n"
    if (error)
        error(message)
    else
        println(logEntry)
    logFile.appendText(logEntry)
}