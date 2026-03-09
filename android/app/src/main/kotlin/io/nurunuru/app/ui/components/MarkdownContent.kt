package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nurunuru.app.ui.theme.LocalNuruColors

// ─── Block types ─────────────────────────────────────────────────────────────

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Code(val code: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class ListItem(val ordered: Boolean, val number: Int, val text: String) : MdBlock()
    object Rule : MdBlock()
    object Blank : MdBlock()
}

// ─── Block parser ─────────────────────────────────────────────────────────────

private fun parseBlocks(content: String): List<MdBlock> {
    val lines = content.split('\n')
    val result = mutableListOf<MdBlock>()
    var i = 0

    while (i < lines.size) {
        val raw = lines[i]
        val trimmed = raw.trimStart()

        when {
            // Fenced code block ``` ... ```
            trimmed.startsWith("```") -> {
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                result += MdBlock.Code(codeLines.joinToString("\n"))
            }

            // Setext h1: underline ===
            i + 1 < lines.size && lines[i + 1].trimStart().matches(Regex("=+")) && raw.isNotBlank() -> {
                result += MdBlock.Heading(1, raw.trim())
                i++ // skip underline
            }

            // Setext h2: underline ---
            i + 1 < lines.size && lines[i + 1].trimStart().matches(Regex("-{2,}")) && raw.isNotBlank() -> {
                result += MdBlock.Heading(2, raw.trim())
                i++ // skip underline
            }

            // ATX headings
            trimmed.startsWith("# ")  -> result += MdBlock.Heading(1, trimmed.removePrefix("# "))
            trimmed.startsWith("## ") -> result += MdBlock.Heading(2, trimmed.removePrefix("## "))
            trimmed.startsWith("### ") -> result += MdBlock.Heading(3, trimmed.removePrefix("### "))
            trimmed.startsWith("#### ") -> result += MdBlock.Heading(4, trimmed.removePrefix("#### "))
            trimmed.startsWith("##### ") -> result += MdBlock.Heading(5, trimmed.removePrefix("##### "))

            // Horizontal rule
            trimmed.matches(Regex("[-*_]{3,}\\s*")) -> result += MdBlock.Rule

            // Blockquote
            trimmed.startsWith("> ") -> result += MdBlock.Quote(trimmed.removePrefix("> "))

            // Unordered list
            trimmed.matches(Regex("[-*+] .*")) ->
                result += MdBlock.ListItem(false, 0, trimmed.substring(2))

            // Ordered list
            trimmed.matches(Regex("\\d+\\. .*")) -> {
                val dot = trimmed.indexOf(". ")
                val num = trimmed.substring(0, dot).toIntOrNull() ?: 1
                result += MdBlock.ListItem(true, num, trimmed.substring(dot + 2))
            }

            // Blank line
            raw.isBlank() -> result += MdBlock.Blank

            // Paragraph: merge consecutive non-special lines
            else -> {
                val paraLines = mutableListOf(raw.trimEnd())
                while (i + 1 < lines.size) {
                    val next = lines[i + 1]
                    val nextT = next.trimStart()
                    if (next.isBlank() ||
                        nextT.startsWith("#") ||
                        nextT.startsWith("```") ||
                        nextT.startsWith(">") ||
                        nextT.matches(Regex("[-*+] .*")) ||
                        nextT.matches(Regex("\\d+\\. .*")) ||
                        nextT.matches(Regex("[-*_]{3,}\\s*"))) break
                    // Hard line break: two trailing spaces
                    paraLines.add(if (paraLines.last().endsWith("  ")) "\n" + next.trimEnd()
                                  else " " + next.trimEnd())
                    i++
                }
                result += MdBlock.Paragraph(paraLines.joinToString(""))
            }
        }
        i++
    }
    return result
}

// ─── Inline parser ────────────────────────────────────────────────────────────

private fun buildInline(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            // Bold+italic ***text***
            text.startsWith("***", i) -> {
                val end = text.indexOf("***", i + 3)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 3, end))
                    }
                    i = end + 3
                } else { append(text[i++]) }
            }
            // Bold **text** or __text__
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i++]) }
            }
            text.startsWith("__", i) -> {
                val end = text.indexOf("__", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i++]) }
            }
            // Italic *text* or _text_
            text[i] == '*' && i + 1 < text.length && text[i + 1] != '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i++]) }
            }
            text[i] == '_' && i + 1 < text.length && text[i + 1] != '_' -> {
                val end = text.indexOf('_', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i++]) }
            }
            // Strikethrough ~~text~~
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i++]) }
            }
            // Inline code `code`
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace,
                        background = Color(0xFF333333), fontSize = 13.sp)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(text[i++]) }
            }
            // Link [text](url)
            text[i] == '[' -> {
                val closeBracket = text.indexOf(']', i + 1)
                if (closeBracket != -1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen != -1) {
                        val linkText = text.substring(i + 1, closeBracket)
                        val url = text.substring(closeBracket + 2, closeParen)
                        pushStringAnnotation("URL", url)
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                            append(linkText)
                        }
                        pop()
                        i = closeParen + 1
                    } else { append(text[i++]) }
                } else { append(text[i++]) }
            }
            else -> append(text[i++])
        }
    }
}

// ─── Composable ───────────────────────────────────────────────────────────────

@Composable
fun MarkdownContent(
    content: String,
    modifier: Modifier = Modifier
) {
    val nuruColors = LocalNuruColors.current
    val uriHandler = LocalUriHandler.current

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var prevWasBlank = false

        for (block in parseBlocks(content)) {
            when (block) {
                is MdBlock.Blank -> {
                    if (!prevWasBlank) Spacer(Modifier.height(6.dp))
                    prevWasBlank = true
                    continue
                }
                is MdBlock.Heading -> {
                    if (block.level <= 2) Spacer(Modifier.height(8.dp))
                    val (fSize, fWeight) = when (block.level) {
                        1 -> 26.sp to FontWeight.Bold
                        2 -> 22.sp to FontWeight.Bold
                        3 -> 18.sp to FontWeight.SemiBold
                        4 -> 16.sp to FontWeight.SemiBold
                        else -> 14.sp to FontWeight.Medium
                    }
                    Text(
                        text = block.text,
                        fontSize = fSize,
                        fontWeight = fWeight,
                        color = nuruColors.textPrimary,
                        lineHeight = fSize * 1.35f
                    )
                    if (block.level == 1) {
                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
                    }
                }
                is MdBlock.Paragraph -> {
                    val annotated = buildInline(block.text, nuruColors.lineGreen)
                    ClickableText(
                        text = annotated,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = nuruColors.textPrimary,
                            lineHeight = 24.sp
                        ),
                        onClick = { offset ->
                            annotated.getStringAnnotations("URL", offset, offset)
                                .firstOrNull()?.let { uriHandler.openUri(it.item) }
                        }
                    )
                }
                is MdBlock.Code -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(nuruColors.bgSecondary, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = block.code,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = nuruColors.textPrimary,
                            lineHeight = 20.sp
                        )
                    }
                }
                is MdBlock.Quote -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .heightIn(min = 20.dp)
                                .background(nuruColors.lineGreen, RoundedCornerShape(2.dp))
                        )
                        Text(
                            text = block.text,
                            color = nuruColors.textSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 22.sp
                        )
                    }
                }
                is MdBlock.ListItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (block.ordered) "${block.number}." else "•",
                            color = nuruColors.textSecondary,
                            fontSize = 15.sp,
                            modifier = Modifier.widthIn(min = 20.dp)
                        )
                        val annotated = buildInline(block.text, nuruColors.lineGreen)
                        ClickableText(
                            text = annotated,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = nuruColors.textPrimary,
                                lineHeight = 22.sp
                            ),
                            onClick = { offset ->
                                annotated.getStringAnnotations("URL", offset, offset)
                                    .firstOrNull()?.let { uriHandler.openUri(it.item) }
                            }
                        )
                    }
                }
                is MdBlock.Rule -> {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = nuruColors.border)
                    Spacer(Modifier.height(4.dp))
                }
            }
            prevWasBlank = false
        }
    }
}
