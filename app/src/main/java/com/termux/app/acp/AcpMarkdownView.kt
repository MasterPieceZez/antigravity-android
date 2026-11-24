package com.termux.app.acp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val MdTextPrimary = Color(0xFFE6EAF0)
private val MdTextSecondary = Color(0xFF94A3B8)
private val MdAccent = Color(0xFF3B82F6)
private val MdCodeBg = Color(0xFF101318)
private val MdCodeHeaderBg = Color(0xFF1B2028)
private val MdBorder = Color(0xFF2A2F38)
private val MdInlineCodeBg = Color(0xFF1F2633)

private val KwColor = Color(0xFFC084FC)
private val StrColor = Color(0xFF86EFAC)
private val NumColor = Color(0xFFFDBA74)
private val CommentColor = Color(0xFF64748B)

private val DiffAddBg = Color(0xFF132A1D)
private val DiffAddText = Color(0xFF4ADE80)
private val DiffDelBg = Color(0xFF2D1518)
private val DiffDelText = Color(0xFFF87171)

private sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class CodeFence(val language: String, val code: String, val isClosed: Boolean) : MarkdownBlock()
    data class Bullet(val marker: String, val text: String) : MarkdownBlock()
    data class Quote(val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
}

@Composable
fun AcpMarkdownView(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { parseStreamingMarkdown(markdown) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Heading -> {
                    val fontSize = when (block.level) {
                        1 -> 18.sp
                        2 -> 16.sp
                        else -> 15.sp
                    }
                    Text(
                        text = buildInlineAnnotated(block.text),
                        color = MdTextPrimary,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Bold,
                        lineHeight = (fontSize.value + 5).sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                is MarkdownBlock.CodeFence -> {
                    CodeBlockView(
                        language = block.language,
                        code = block.code,
                        isStreaming = !block.isClosed
                    )
                }

                is MarkdownBlock.Bullet -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = block.marker,
                            color = MdAccent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(
                            text = buildInlineAnnotated(block.text),
                            color = MdTextPrimary,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is MarkdownBlock.Quote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF14171D))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(18.dp)
                                .background(MdAccent)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = buildInlineAnnotated(block.text),
                            color = MdTextSecondary,
                            fontSize = 13.sp,
                            fontStyle = FontStyle.Italic,
                            lineHeight = 19.sp
                        )
                    }
                }

                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = buildInlineAnnotated(block.text),
                        color = MdTextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 21.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlockView(
    language: String,
    code: String,
    isStreaming: Boolean
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500L)
            copied = false
        }
    }

    val highlighted = remember(code, language) {
        highlightCode(code, language)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MdCodeBg)
            .border(1.dp, MdBorder, RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MdCodeHeaderBg)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val badge = language.ifBlank { "code" } + if (isStreaming) " · streaming" else ""
            Text(
                text = badge,
                color = MdTextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (copied) "Copied" else "Copy",
                color = if (copied) DiffAddText else MdAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("code", code))
                        copied = true
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            Text(
                text = highlighted,
                color = MdTextPrimary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
fun UnifiedDiffBlock(
    path: String?,
    oldText: String?,
    newText: String?,
    modifier: Modifier = Modifier
) {
    val diffLines = remember(oldText, newText) {
        computeUnifiedDiffLines(oldText.orEmpty(), newText.orEmpty())
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MdCodeBg)
            .border(1.dp, MdBorder, RoundedCornerShape(8.dp))
    ) {
        if (!path.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MdCodeHeaderBg)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = path,
                    color = MdTextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 6.dp)
        ) {
            for (line in diffLines) {
                val (bg, fg) = when {
                    line.startsWith("+") -> DiffAddBg to DiffAddText
                    line.startsWith("-") -> DiffDelBg to DiffDelText
                    else -> Color.Transparent to MdTextSecondary
                }
                Text(
                    text = line,
                    color = fg,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bg)
                        .padding(horizontal = 10.dp, vertical = 1.dp)
                )
            }
        }
    }
}

private fun parseStreamingMarkdown(markdown: String): List<MarkdownBlock> {
    if (markdown.isEmpty()) return emptyList()
    val lines = markdown.replace("\r\n", "\n").split("\n")
    val blocks = ArrayList<MarkdownBlock>()
    val paragraphLines = ArrayList<String>()

    var inFence = false
    var fenceLang = ""
    val fenceLines = ArrayList<String>()

    fun flushParagraph() {
        if (paragraphLines.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(paragraphLines.joinToString("\n")))
            paragraphLines.clear()
        }
    }

    for (line in lines) {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            if (!inFence) {
                flushParagraph()
                inFence = true
                fenceLang = trimmed.removePrefix("```").trim()
                fenceLines.clear()
            } else {
                blocks.add(
                    MarkdownBlock.CodeFence(
                        language = fenceLang,
                        code = fenceLines.joinToString("\n"),
                        isClosed = true
                    )
                )
                inFence = false
                fenceLang = ""
                fenceLines.clear()
            }
            continue
        }

        if (inFence) {
            fenceLines.add(line)
            continue
        }

        if (trimmed.isEmpty()) {
            flushParagraph()
            continue
        }

        val headingMatch = Regex("^(#{1,4})\\s+(.+)$").find(trimmed)
        if (headingMatch != null) {
            flushParagraph()
            blocks.add(
                MarkdownBlock.Heading(
                    level = headingMatch.groupValues[1].length,
                    text = headingMatch.groupValues[2].trim()
                )
            )
            continue
        }

        if (trimmed.startsWith("> ")) {
            flushParagraph()
            blocks.add(MarkdownBlock.Quote(trimmed.removePrefix("> ").trim()))
            continue
        }

        val bulletMatch = Regex("^([-*•]|\\d+\\.)\\s+(.+)$").find(trimmed)
        if (bulletMatch != null) {
            flushParagraph()
            val marker = if (bulletMatch.groupValues[1] in listOf("-", "*", "•")) "•" else bulletMatch.groupValues[1]
            blocks.add(MarkdownBlock.Bullet(marker, bulletMatch.groupValues[2]))
            continue
        }

        paragraphLines.add(line)
    }

    if (inFence) {
        // Streaming-safe: emit unclosed code fence cleanly while tokens arrive
        blocks.add(
            MarkdownBlock.CodeFence(
                language = fenceLang,
                code = fenceLines.joinToString("\n"),
                isClosed = false
            )
        )
    } else {
        flushParagraph()
    }

    return blocks
}

private fun buildInlineAnnotated(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val len = text.length
    while (i < len) {
        // Inline code `...`
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i) {
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = MdInlineCodeBg,
                        color = Color(0xFF93C5FD),
                        fontSize = 13.sp
                    )
                )
                append(" ${text.substring(i + 1, end)} ")
                pop()
                i = end + 1
                continue
            }
        }

        // Bold **...**
        if (i + 1 < len && text[i] == '*' && text[i + 1] == '*') {
            val end = text.indexOf("**", i + 2)
            if (end > i + 1) {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MdTextPrimary))
                append(text.substring(i + 2, end))
                pop()
                i = end + 2
                continue
            }
        }

        // Italic *...*
        if (text[i] == '*') {
            val end = text.indexOf('*', i + 1)
            if (end > i + 1 && !text.substring(i + 1, end).contains('\n')) {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(text.substring(i + 1, end))
                pop()
                i = end + 1
                continue
            }
        }

        // Link [label](url)
        if (text[i] == '[') {
            val closeBracket = text.indexOf(']', i + 1)
            if (closeBracket > i && closeBracket + 1 < len && text[closeBracket + 1] == '(') {
                val closeParen = text.indexOf(')', closeBracket + 2)
                if (closeParen > closeBracket + 1) {
                    val label = text.substring(i + 1, closeBracket)
                    pushStyle(
                        SpanStyle(
                            color = MdAccent,
                            textDecoration = TextDecoration.Underline
                        )
                    )
                    append(label)
                    pop()
                    i = closeParen + 1
                    continue
                }
            }
        }

        append(text[i])
        i++
    }
}

private val CODE_KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "enum", "sealed", "data",
    "return", "if", "else", "when", "for", "while", "try", "catch", "finally", "throw",
    "import", "package", "private", "public", "protected", "internal", "override", "suspend",
    "true", "false", "null", "this", "super", "in", "is", "as", "by", "companion", "init",
    "def", "lambda", "async", "await", "yield", "from", "with", "pass", "None", "True", "False",
    "const", "let", "function", "export", "default", "new", "typeof", "struct", "impl", "fn",
    "pub", "mut", "match", "use", "mod", "crate", "int", "void", "bool", "float", "double",
    "static", "final", "extends", "implements", "echo", "fi", "then", "done", "esac"
)

private val TOKEN_REGEX = Regex(
    """(//[^\n]*|#[^\n]*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\b\d+(?:\.\d+)?\b|\b[A-Za-z_][A-Za-z0-9_]*\b)"""
)

private fun highlightCode(code: String, language: String): AnnotatedString = buildAnnotatedString {
    append(code)
    val lang = language.lowercase()
    val supportsHashComment = lang in setOf("py", "python", "sh", "bash", "zsh", "yaml", "yml", "toml", "rb", "ruby")
    for (match in TOKEN_REGEX.findAll(code)) {
        val token = match.value
        val start = match.range.first
        val end = match.range.last + 1
        val color = when {
            token.startsWith("//") -> CommentColor
            token.startsWith("#") && supportsHashComment -> CommentColor
            token.startsWith("\"") || token.startsWith("'") -> StrColor
            token[0].isDigit() -> NumColor
            token in CODE_KEYWORDS -> KwColor
            else -> null
        }
        if (color != null) {
            addStyle(SpanStyle(color = color), start, end)
        }
    }
}

private fun computeUnifiedDiffLines(oldText: String, newText: String): List<String> {
    if (oldText.isEmpty() && newText.isEmpty()) return listOf(" (no diff content)")
    val oldLines = if (oldText.isEmpty()) emptyList() else oldText.replace("\r\n", "\n").split("\n")
    val newLines = if (newText.isEmpty()) emptyList() else newText.replace("\r\n", "\n").split("\n")

    if (oldLines.isEmpty()) return newLines.map { "+ $it" }
    if (newLines.isEmpty()) return oldLines.map { "- $it" }

    // For very large files, fall back to direct removed/added rendering to stay snappy on mobile
    if (oldLines.size * newLines.size > 40_000) {
        val out = ArrayList<String>(oldLines.size + newLines.size)
        oldLines.forEach { out.add("- $it") }
        newLines.forEach { out.add("+ $it") }
        return out
    }

    val m = oldLines.size
    val n = newLines.size
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in m - 1 downTo 0) {
        for (j in n - 1 downTo 0) {
            dp[i][j] = if (oldLines[i] == newLines[j]) {
                dp[i + 1][j + 1] + 1
            } else {
                maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
    }

    val result = ArrayList<String>()
    var i = 0
    var j = 0
    while (i < m && j < n) {
        when {
            oldLines[i] == newLines[j] -> {
                result.add("  ${oldLines[i]}")
                i++
                j++
            }
            dp[i + 1][j] >= dp[i][j + 1] -> {
                result.add("- ${oldLines[i]}")
                i++
            }
            else -> {
                result.add("+ ${newLines[j]}")
                j++
            }
        }
    }
    while (i < m) {
        result.add("- ${oldLines[i]}")
        i++
    }
    while (j < n) {
        result.add("+ ${newLines[j]}")
        j++
    }
    return result
}
