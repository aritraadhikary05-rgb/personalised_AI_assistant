package com.thor.assistant

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class ThorTerminalActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var output: TextView
    private lateinit var commandInput: EditText
    private lateinit var runButton: Button
    private lateinit var aiButton: Button
    private lateinit var workspace: java.io.File
    private lateinit var currentDirectory: java.io.File
    private var busy = false
    private var approvalPending = false

    private val blockedPatterns = listOf(
        Regex("""(^|[;&|\s])su(\s|$)""", RegexOption.IGNORE_CASE),
        Regex("""\b(sudo|doas|reboot|poweroff|halt|mkfs|dd|mount|umount|setprop|setenforce)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(pm|am|settings|svc)\s+(disable|enable|grant|revoke|put|delete|reset|force-stop|uninstall|install)\b""", RegexOption.IGNORE_CASE),
        Regex("""\brm\s+(-[^\s]*r[^\s]*f|-rf|-fr)\s+(/|\.\.)""", RegexOption.IGNORE_CASE),
        Regex("""(^|\s)(/system|/vendor|/product|/proc|/sys)(/|\s|$)"""),
        Regex("""(^|/)\.\.(/|$)"""),
        Regex("""(^|\s)/"""),
        Regex("""\bln\s+-s\b""", RegexOption.IGNORE_CASE)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        workspace = java.io.File(filesDir, "thor_terminal").apply { mkdirs() }
        currentDirectory = workspace
        buildTerminalUi()
        append("THOR TERMINAL 1.0")
        append("Android app sandbox | Working directory: ${workspace.absolutePath}")
        append("Type 'help' for commands. Use 'ask <task>' for AI-generated shell scripts.")
        append("")
        appendPrompt()
    }

    private fun buildTerminalUi() {
        val background = Color.rgb(11, 13, 18)
        val panel = Color.rgb(25, 28, 35)
        val green = Color.rgb(10, 132, 255)
        val muted = Color.rgb(145, 166, 185)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(10))
            setBackgroundColor(background)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "THOR / TERMINAL"
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(green)
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(Button(this).apply {
            text = "CLOSE"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(88), dp(42)))
        root.addView(header)

        val terminalScroll = ScrollView(this).apply {
            isFillViewport = true
            setBackground(terminalSurface(panel))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        output = TextView(this).apply {
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(213, 229, 239))
            setTextIsSelectable(true)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        terminalScroll.addView(output)
        root.addView(terminalScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(8); bottomMargin = dp(8) })

        root.addView(TextView(this).apply {
            text = "COMMAND INPUT"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(muted)
        })
        commandInput = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            hint = "thor$  type a command..."
            setHintTextColor(muted)
            setTextColor(Color.WHITE)
            setPadding(dp(10), 0, dp(10), 0)
            setBackground(terminalSurface(panel))
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { runManualCommand(); true } else false
            }
        }
        root.addView(commandInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50)
        ).apply { topMargin = dp(5) })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        runButton = Button(this).apply {
            text = "RUN"
            setOnClickListener { runManualCommand() }
        }
        aiButton = Button(this).apply {
            text = "ASK THOR AI"
            setOnClickListener { requestAiScript() }
        }
        actions.addView(runButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(aiButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(5) })
        actions.addView(Button(this).apply {
            text = "CLEAR"
            setOnClickListener { output.text = ""; appendPrompt() }
        }, LinearLayout.LayoutParams(0, dp(48), 0.8f).apply { leftMargin = dp(5) })
        root.addView(actions)
        root.addView(TextView(this).apply {
            text = "AI-generated scripts are shown for review and require approval before execution."
            textSize = 10f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, 0)
        })
        setContentView(root)
    }

    private fun runManualCommand() {
        val command = commandInput.text.toString().trim()
        if (command.isBlank() || busy) return
        commandInput.text.clear()
        append("thor$ $command")
        when {
            command.equals("clear", true) || command.equals("cls", true) -> {
                output.text = ""
                appendPrompt()
            }
            command.equals("help", true) -> {
                append("Commands: help, pwd, ls, cd, mkdir, touch, cat, echo, date, whoami, clear")
                append("Only THOR's restricted app-private commands are supported; arbitrary Android shell commands are disabled.")
                append("AI coding: type ask <what you want built> or use ASK THOR AI.")
                appendPrompt()
            }
            command.equals("pwd", true) -> { append(currentDirectory.absolutePath); appendPrompt() }
            command.startsWith("cd ", true) -> changeDirectory(command.substringAfter(" ").trim())
            command.equals("cd", true) -> changeDirectory("")
            command.startsWith("ask ", true) -> {
                commandInput.setText(command.substringAfter(' '))
                requestAiScript()
            }
            else -> executeCommand(command)
        }
    }

    private fun changeDirectory(path: String) {
        val target = if (path.isBlank() || path == "~") workspace else java.io.File(currentDirectory, path)
        val resolved = runCatching { target.canonicalFile }.getOrNull()
        if (resolved == null || !(resolved == workspace.canonicalFile || resolved.path.startsWith(workspace.canonicalPath + java.io.File.separator))) {
            appendError("Directory is outside THOR's private workspace.")
        } else if (!resolved.isDirectory) {
            appendError("Directory not found: $path")
        } else {
            currentDirectory = resolved
            append(currentDirectory.absolutePath)
        }
        appendPrompt()
    }

    /**
     * THOR's terminal intentionally uses a small, app-private command interpreter
     * instead of executing arbitrary shell code with the app's Android UID.
     * This prevents generated scripts from accessing app credentials, network
     * sockets, Android system commands, or files outside the private workspace.
     */
    private fun executeCommand(command: String, scriptFile: java.io.File? = null) {
        setBusy(true)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val commands = if (scriptFile != null) {
                        require(scriptFile.canonicalFile.parentFile == workspace.canonicalFile) {
                            "Script is outside THOR's private workspace."
                        }
                        require(scriptFile.length() <= 32_000L) { "Script is too large." }
                        scriptFile.readLines().also {
                            require(it.size <= 100) { "Script exceeds the 100-line limit." }
                        }
                    } else listOf(command)

                    val outputLines = mutableListOf<String>()
                    for (line in commands) {
                        val trimmed = line.trim()
                        if (trimmed.isBlank() || trimmed.startsWith("#")) continue
                        val resultLine = runRestrictedCommand(trimmed)
                        if (resultLine.startsWith("[ERROR]")) {
                            outputLines.add(resultLine)
                            break
                        }
                        if (resultLine.isNotBlank()) outputLines.add(resultLine)
                    }
                    outputLines.joinToString("\n").ifBlank { "Completed." }
                }
                append(result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                appendError("Execution failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                setBusy(false)
                appendPrompt()
            }
        }
    }

    private fun tokenizeRestrictedCommand(command: String): List<String> {
        require(command.length <= 2_000) { "Command is too long." }
        require(command.none { it in ";|&$()<>\u0060\\\n\r" }) {
            "Shell operators, substitutions, escapes, and command chaining are not supported."
        }
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var tokenStarted = false
        for (character in command) {
            when {
                quote != null && character == quote -> quote = null
                quote == null && (character == '\'' || character == '"') -> {
                    quote = character
                    tokenStarted = true
                }
                quote == null && character.isWhitespace() -> {
                    if (tokenStarted) {
                        tokens.add(token.toString())
                        token.setLength(0)
                        tokenStarted = false
                    }
                }
                else -> {
                    token.append(character)
                    tokenStarted = true
                }
            }
        }
        require(quote == null) { "Unclosed quote." }
        if (tokenStarted) tokens.add(token.toString())
        return tokens
    }

    private fun safeWorkspaceFile(path: String): java.io.File {
        require(path.isNotBlank()) { "A file or directory name is required." }
        val base = currentDirectory.canonicalFile
        val resolved = java.io.File(base, path).canonicalFile
        require(
            resolved == workspace.canonicalFile ||
                resolved.path.startsWith(workspace.canonicalPath + java.io.File.separator)
        ) { "Path is outside THOR's private workspace." }
        return resolved
    }

    private fun runRestrictedCommand(command: String): String {
        return try {
            val args = tokenizeRestrictedCommand(command)
            if (args.isEmpty()) return ""
            when (args[0].lowercase(java.util.Locale.ROOT)) {
                "help" -> "Commands: help, pwd, ls [path], mkdir <name>, touch <file>, write <file> <text>, cat <file>, date, whoami, echo <text>, clear"
                "pwd" -> {
                    require(args.size == 1) { "Usage: pwd" }
                    currentDirectory.canonicalPath
                }
                "whoami" -> {
                    require(args.size == 1) { "Usage: whoami" }
                    "THOR app sandbox (UID ${android.os.Process.myUid()})"
                }
                "date" -> {
                    require(args.size == 1) { "Usage: date" }
                    java.text.DateFormat.getDateTimeInstance().format(java.util.Date())
                }
                "echo" -> args.drop(1).joinToString(" ")
                "ls" -> {
                    require(args.size <= 2) { "Usage: ls [path]" }
                    val directory = safeWorkspaceFile(args.getOrNull(1) ?: ".")
                    require(directory.isDirectory) { "Not a directory: ${args.getOrNull(1) ?: "."}" }
                    directory.listFiles()?.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
                        ?.joinToString("\n") { file ->
                            (if (file.isDirectory) "[DIR] " else "      ") + file.name
                        }.orEmpty().ifBlank { "(empty)" }
                }
                "mkdir" -> {
                    require(args.size == 2) { "Usage: mkdir <name>" }
                    val directory = safeWorkspaceFile(args[1])
                    require(directory.mkdirs() || directory.isDirectory) { "Could not create directory." }
                    "Created directory: ${directory.name}"
                }
                "touch" -> {
                    require(args.size == 2) { "Usage: touch <file>" }
                    val file = safeWorkspaceFile(args[1])
                    require(file.parentFile?.isDirectory == true) { "Parent directory does not exist." }
                    if (!file.exists()) require(file.createNewFile()) { "Could not create file." }
                    else file.setLastModified(System.currentTimeMillis())
                    "Updated file: ${file.name}"
                }
                "write" -> {
                    require(args.size >= 3) { "Usage: write <file> <text>" }
                    val file = safeWorkspaceFile(args[1])
                    require(file.parentFile?.isDirectory == true) { "Parent directory does not exist." }
                    val content = args.drop(2).joinToString(" ")
                    require(content.length <= 16_000) { "Text is too long (maximum 16 KB)." }
                    file.writeText(content, Charsets.UTF_8)
                    "Wrote ${content.length} characters to ${file.name}"
                }
                "cat" -> {
                    require(args.size == 2) { "Usage: cat <file>" }
                    val file = safeWorkspaceFile(args[1])
                    require(file.isFile) { "File not found: ${file.name}" }
                    require(file.length() <= 64_000L) { "File is too large to display (maximum 64 KB)." }
                    file.readText(Charsets.UTF_8)
                }
                "clear", "cls" -> {
                    require(args.size == 1) { "Usage: clear" }
                    output.text = ""
                    "Terminal cleared."
                }
                else -> error("Unsupported command '${args[0]}'. THOR only runs app-private file and information commands.")
            }
        } catch (error: Exception) {
            "[ERROR] ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun isBlocked(script: String): Boolean {
        val lines = script.lines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        if (lines.isEmpty() || lines.size > 100) return true
        return lines.any { line ->
            val args = runCatching { tokenizeRestrictedCommand(line) }.getOrNull()
            args.isNullOrEmpty() || args[0].lowercase(java.util.Locale.ROOT) !in setOf(
                "help", "pwd", "whoami", "date", "echo", "ls", "mkdir", "touch", "write", "cat", "clear", "cls"
            )
        }
    }

    private fun requestAiScript() {
        if (busy) return
        val task = commandInput.text.toString().trim()
        if (task.isBlank()) {
            Toast.makeText(this, "Describe what you want THOR to build.", Toast.LENGTH_SHORT).show()
            return
        }
        commandInput.text.clear()
        append("thor/ai$ $task")
        setBusy(true)
        scope.launch {
            try {
                val provider = ThorAiProviderManager(this@ThorTerminalActivity)
                val settings = provider.settings()
                val prompt = """
                    You are THOR's coding assistant for its built-in Android app terminal.
                    Produce one POSIX /system/bin/sh script for the user's task.
                    The script runs with the app's ordinary Android UID in this private working directory:
                    ${workspace.absolutePath}
                    Do not use root, sudo, su, package managers, Android settings commands, protected paths,
                    network downloads, destructive recursive deletion, or commands that leave this directory.
                    Prefer creating useful files in the current directory. Keep the script concise.
                    Return a short explanation followed by exactly one fenced bash code block.
                    User task: ${task.take(1200)}
                """.trimIndent()
                val answer = when (settings.provider) {
                    ThorAiProviderManager.Provider.GEMINI -> GeminiAssistant.ask(prompt)
                    ThorAiProviderManager.Provider.LOCAL -> {
                        val offline = ThorOfflineAi(this@ThorTerminalActivity)
                        if (!offline.isReady()) offline.initialize()
                        offline.ask(prompt)
                    }
                    else -> provider.ask(prompt, 512)
                }
                val fence = 96.toChar().toString().repeat(3)
                val start = answer.indexOf(fence)
                val end = if (start >= 0) answer.indexOf(fence, start + fence.length) else -1
                val code = if (start >= 0 && end > start) {
                    answer.substring(start + fence.length, end).substringAfter('\n').trim()
                } else ""
                if (code.isBlank()) {
                    append("THOR AI RESPONSE:")
                    append(answer.take(5000))
                    appendError("No fenced shell script was returned. Ask THOR to provide a bash script.")
                } else if (isBlocked(code)) {
                    append("THOR AI EXPLANATION:")
                    append(answer.substringBefore(fence).take(1800))
                    appendError("The script uses commands outside THOR's restricted app-private command set. It was not executed.")
                } else {
                    append("THOR AI PLAN:")
                    append(answer.substringBefore(fence).take(1800).ifBlank { "Script prepared for review." })
                    append("PROPOSED SCRIPT:")
                    append(code.take(12000))
                    approvalPending = true
                    AlertDialog.Builder(this@ThorTerminalActivity)
                        .setTitle("Run AI-generated script?")
                        .setMessage("Review the script above. It will run as THOR's app user, inside the private workspace. Continue?")
                        .setNegativeButton("CANCEL") { _, _ ->
                            approvalPending = false
                            append("Execution cancelled by user.")
                            setBusy(false)
                            appendPrompt()
                        }
                        .setPositiveButton("RUN SCRIPT") { _, _ ->
                            val script = java.io.File(workspace, "thor_ai_task.sh")
                            script.writeText(code)
                            approvalPending = false
                            append("Executing ./thor_ai_task.sh ...")
                            setBusy(false)
                            executeCommand("AI script", script)
                        }
                        .setOnCancelListener {
                            approvalPending = false
                            setBusy(false)
                            appendPrompt()
                        }
                        .show()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                appendError("AI request failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                if (busy && !approvalPending) {
                    setBusy(false)
                    appendPrompt()
                }
            }
        }
    }

    private fun isBlocked(command: String): Boolean = blockedPatterns.any { it.containsMatchIn(command) }

    private fun append(value: String) {
        output.append(value)
        output.append("\n")
        (output.parent as? ScrollView)?.post { (output.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendError(value: String) = append("[THOR GUARD] $value")
    private fun appendPrompt() { output.append("thor$ ") }

    private fun setBusy(value: Boolean) {
        busy = value
        runButton.isEnabled = !value
        aiButton.isEnabled = !value
        commandInput.isEnabled = !value
        runButton.text = if (value) "RUNNING..." else "RUN"
    }


    private fun terminalSurface(fill: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(fill)
            setStroke(dp(1), Color.rgb(55, 59, 69))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
