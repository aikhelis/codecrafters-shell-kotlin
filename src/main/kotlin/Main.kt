import java.io.File
import java.io.FileWriter
import kotlin.system.exitProcess

// Track the current working directory
var currentWorkingDirectory: String? = File(".").canonicalPath

fun main() {
    while (true) {
        print("$ ")
        val command = readln()
        handleCommand(command)
    }
}

fun handleCommand(command: String) {
    // Parse command for redirection
    val parsedCommand = parseCommandWithRedirection(command)

    // If redirection is specified, handle it specially
    if (parsedCommand.redirectOutput != null || parsedCommand.redirectError != null) {
        executeWithRedirection(parsedCommand)
        return
    }

    // Handle regular commands without redirection
    when {
        parsedCommand.command == "exit 0" -> exitShell()
        parsedCommand.command == "pwd" -> handlePwd()
        parsedCommand.command.startsWith("echo ") -> handleEcho(parsedCommand.command)
        parsedCommand.command.startsWith("type ") -> handleType(parsedCommand.command)
        parsedCommand.command.startsWith("cd ") -> handleCd(parsedCommand.command)
        else -> handleExternalCommand(parsedCommand.command)
    }
}

fun exitShell() {
    exitProcess(0)
}

fun handlePwd() {
    println(currentWorkingDirectory)
}

fun parseCommandWithQuotes(command: String): List<String> {
    val result = mutableListOf<String>()
    var current = StringBuilder()
    var i = 0
    var inSingleQuotes = false
    var inDoubleQuotes = false

    while (i < command.length) {
        val char = command[i]

        when {
            char == '\\' && !inSingleQuotes -> {
                // Backslash escaping
                if (i + 1 < command.length) {
                    val nextChar = command[i + 1]
                    if (inDoubleQuotes) {
                        // Within double quotes, only escape special characters
                        when (nextChar) {
                            '"', '\\', '$', '`', '\n' -> {
                                current.append(nextChar)
                                i++ // Skip the next character since we've processed it
                            }
                            else -> {
                                // Not a special character, preserve the backslash
                                current.append(char)
                            }
                        }
                    } else {
                        // Outside quotes, escape any character
                        current.append(nextChar)
                        i++ // Skip the next character since we've processed it
                    }
                } else {
                    // Backslash at end of string, treat literally
                    current.append(char)
                }
            }
            char == '\'' && !inDoubleQuotes -> {
                inSingleQuotes = !inSingleQuotes
            }
            char == '"' && !inSingleQuotes -> {
                inDoubleQuotes = !inDoubleQuotes
            }
            char == ' ' && !inSingleQuotes && !inDoubleQuotes -> {
                if (current.isNotEmpty()) {
                    result.add(current.toString())
                    current = StringBuilder()
                }
                // Skip consecutive spaces
                while (i + 1 < command.length && command[i + 1] == ' ') {
                    i++
                }
            }
            else -> {
                current.append(char)
            }
        }
        i++
    }

    if (current.isNotEmpty()) {
        result.add(current.toString())
    }

    return result
}

fun handleEcho(command: String) {
    val parts = parseCommandWithQuotes(command)
    if (parts.size > 1) {
        // Join all arguments after "echo" with single spaces
        val output = parts.drop(1).joinToString(" ")
        println(output)
    } else {
        // No arguments after echo
        println()
    }
}

fun handleType(command: String) {
    val searchCommand = COMMAND_REGEX["type"]!!.find(command)?.groupValues?.get(1)
    if (searchCommand in BUILTIN_COMMANDS) {
        println("$searchCommand is a shell builtin")
    } else {
        val executablePath = findExecutableInPath(searchCommand ?: "")
        if (executablePath != null) {
            println("$searchCommand is $executablePath")
        } else {
            println("$searchCommand: not found")
        }
    }
}

fun handleCd(command: String) {
    val path = command.removePrefix("cd ").trim()
    val targetPath = when {
        path.startsWith("~") -> {
            // Check HOME environment variable first, fall back to system property
            val homeDir = System.getenv("HOME") ?: System.getProperty("user.home")
            if (path == "~") {
                homeDir
            } else {
                homeDir + path.substring(1)
            }
        }
        else -> path
    }

    val dir = File(targetPath)
    val resolvedDir = if (dir.isAbsolute) dir else File(currentWorkingDirectory, targetPath)

    if (resolvedDir.exists() && resolvedDir.isDirectory) {
        currentWorkingDirectory = resolvedDir.canonicalPath
        // No output on success
    } else {
        println("cd: $path: No such file or directory")
    }
}

// Data class to represent a command with potential output redirection
data class ParsedCommand(
    val command: String,
    val redirectOutput: String? = null, // File path for stdout redirection (> or 1>)
    val redirectError: String? = null   // File path for stderr redirection (2>)
)

// Parse command for redirection operators (> and 1>)
fun parseCommandWithRedirection(command: String): ParsedCommand {
    val redirectPattern = Regex("""(.+?)\s+(1?>|2>)\s+(.+)""")
    val match = redirectPattern.find(command.trim())

    return if (match != null) {
        val (commandPart, operator, filePath) = match.destructured
        when (operator) {
            ">" -> ParsedCommand(commandPart.trim(), filePath.trim())
            "1>" -> ParsedCommand(commandPart.trim(), filePath.trim())
            "2>" -> ParsedCommand(commandPart.trim(), null, filePath.trim())
            else -> ParsedCommand(commandPart.trim())
        }
    } else {
        ParsedCommand(command.trim())
    }
}

fun handleExternalCommand(command: String) {
    val parts = parseCommandWithQuotes(command)
    val programName = parts[0]
    val executablePath = findExecutableInPath(programName)
    if (executablePath != null) {
        executeProgram(executablePath, parts.drop(1))
    } else {
        println("$command: command not found")
    }
}

fun executeProgram(executablePath: String, args: List<String>) {
    try {
        val programName = File(executablePath).name
        val processBuilder = ProcessBuilder(listOf(programName) + args)
        processBuilder.directory(File(executablePath).parentFile)
        val process = processBuilder.start()

        // Read and print stdout
        process.inputStream.bufferedReader().use { reader ->
            reader.lines().forEach { println(it) }
        }

        // Read and print stderr
        process.errorStream.bufferedReader().use { reader ->
            reader.lines().forEach { println(it) }
        }

        process.waitFor()
    } catch (e: Exception) {
        println("Error executing program: ${e.message}")
    }
}

// Execute command with output redirection
fun executeWithRedirection(parsedCommand: ParsedCommand) {
    try {
        // Handle builtin commands with redirection
        when {
            parsedCommand.command == "echo" -> {
                // Echo doesn't produce stderr, so for 2> redirection, just output normally
                if (parsedCommand.redirectError != null) {
                    // Create the error file but echo goes to stdout as normal
                    val errorFile = File(parsedCommand.redirectError)
                    errorFile.parentFile?.mkdirs()
                    // Create the empty error file since echo has no stderr
                    errorFile.writeText("")
                    println()
                } else {
                    // Stdout redirection
                    val outputFile = File(parsedCommand.redirectOutput!!)
                    outputFile.parentFile?.mkdirs()
                    FileWriter(outputFile, false).use { writer ->
                        writer.write("\n")
                    }
                }
            }
            parsedCommand.command.startsWith("echo ") -> {
                val parts = parseCommandWithQuotes(parsedCommand.command)
                val output = if (parts.size > 1) parts.drop(1).joinToString(" ") else ""

                if (parsedCommand.redirectError != null) {
                    // Echo doesn't produce stderr, so output goes to stdout as normal
                    val errorFile = File(parsedCommand.redirectError)
                    errorFile.parentFile?.mkdirs()
                    // Create the empty error file since echo has no stderr
                    errorFile.writeText("")
                    println(output)
                } else {
                    // Stdout redirection
                    val outputFile = File(parsedCommand.redirectOutput!!)
                    outputFile.parentFile?.mkdirs()
                    FileWriter(outputFile, false).use { writer ->
                        writer.write(output + "\n")
                    }
                }
            }
            parsedCommand.command == "pwd" -> {
                if (parsedCommand.redirectError != null) {
                    // pwd doesn't produce stderr, so output goes to stdout as normal
                    val errorFile = File(parsedCommand.redirectError)
                    errorFile.parentFile?.mkdirs()
                    println(currentWorkingDirectory)
                } else {
                    // Stdout redirection
                    val outputFile = File(parsedCommand.redirectOutput!!)
                    outputFile.parentFile?.mkdirs()
                    FileWriter(outputFile, false).use { writer ->
                        writer.write(currentWorkingDirectory + "\n")
                    }
                }
            }
            parsedCommand.command.startsWith("type ") -> {
                val searchCommand = COMMAND_REGEX["type"]!!.find(parsedCommand.command)?.groupValues?.get(1)
                val output = if (searchCommand in BUILTIN_COMMANDS) {
                    "$searchCommand is a shell builtin"
                } else {
                    val executablePath = findExecutableInPath(searchCommand ?: "")
                    if (executablePath != null) {
                        "$searchCommand is $executablePath"
                    } else {
                        "$searchCommand: not found"
                    }
                }

                if (parsedCommand.redirectError != null) {
                    // type doesn't produce stderr, so output goes to stdout as normal
                    val errorFile = File(parsedCommand.redirectError)
                    errorFile.parentFile?.mkdirs()
                    println(output)
                } else {
                    // Stdout redirection
                    val outputFile = File(parsedCommand.redirectOutput!!)
                    outputFile.parentFile?.mkdirs()
                    FileWriter(outputFile, false).use { writer ->
                        writer.write(output + "\n")
                    }
                }
            }
            else -> {
                // Handle external commands with redirection
                executeExternalWithRedirection(parsedCommand)
            }
        }
    } catch (e: Exception) {
        println("Error executing command with redirection: ${e.message}")
    }
}

// Execute external command with output redirection
fun executeExternalWithRedirection(parsedCommand: ParsedCommand) {
    val parts = parseCommandWithQuotes(parsedCommand.command)
    if (parts.isEmpty()) return

    val programName = parts[0]
    val executablePath = findExecutableInPath(programName)
    if (executablePath != null) {
        try {
            val processBuilder = ProcessBuilder(listOf(programName) + parts.drop(1))
            processBuilder.directory(File(executablePath).parentFile)
            val process = processBuilder.start()

            // Handle stdout redirection
            if (parsedCommand.redirectOutput != null) {
                val outputFile = File(parsedCommand.redirectOutput)
                outputFile.parentFile?.mkdirs()
                FileWriter(outputFile, false).use { writer ->
                    process.inputStream.bufferedReader().use { reader ->
                        reader.lines().forEach { line ->
                            writer.write(line + "\n")
                        }
                    }
                }
            } else {
                // Print stdout to console
                process.inputStream.bufferedReader().use { reader ->
                    reader.lines().forEach { println(it) }
                }
            }

            // Handle stderr redirection
            if (parsedCommand.redirectError != null) {
                val errorFile = File(parsedCommand.redirectError)
                errorFile.parentFile?.mkdirs()
                FileWriter(errorFile, false).use { writer ->
                    process.errorStream.bufferedReader().use { reader ->
                        reader.lines().forEach { line ->
                            writer.write(line + "\n")
                        }
                    }
                }
            } else {
                // Print stderr to console
                process.errorStream.bufferedReader().use { reader ->
                    reader.lines().forEach { println(it) }
                }
            }

            process.waitFor()
        } catch (e: Exception) {
            println("Error executing external program: ${e.message}")
        }
    } else {
        println("${parsedCommand.command}: command not found")
    }
}

fun findExecutableInPath(command: String): String? {
    val pathEnv = System.getenv("PATH") ?: return null
    val pathDirectories = pathEnv.split(":")

    for (directory in pathDirectories) {
        val file = File(directory, command)
        if (file.exists() && file.canExecute()) {
            return file.absolutePath
        }
    }
    return null
}

val BUILTIN_COMMANDS = setOf(
    "exit",
    "echo",
    "type",
    "pwd",
    "cd",
)

val COMMAND_REGEX = mapOf(
    "echo" to Regex("echo (.*)"),
    "type" to Regex("type (.*)"),
    "exit" to Regex("exit 0"),
    "ls" to Regex("ls"),
    "pwd" to Regex("pwd"),
    "cd" to Regex("cd (.*)")
)

operator fun Regex.contains(text: CharSequence): Boolean = this.matches(text)