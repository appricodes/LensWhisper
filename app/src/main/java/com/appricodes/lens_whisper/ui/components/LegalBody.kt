package com.appricodes.lens_whisper.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders a long legal document as one [Text] per paragraph rather than one [Text] for the whole
 * thing.
 *
 * This matters far more for this app's users than it looks. With TalkBack, a single text node is a
 * single stop in the swipe order: the user either hears the entire notice from the top or nothing,
 * with no way to move a paragraph at a time, re-hear one point, or pick up where they lost the
 * thread. Split into blocks, every paragraph and every bullet is its own stop, and the numbered
 * section titles carry [heading] semantics so TalkBack's "navigate by heading" control can jump
 * straight to, say, section 2.
 *
 * Splitting rules, driven by how [com.appricodes.lens_whisper.legal.LegalTexts] is written:
 * - blank line separates blocks;
 * - a block that is one line of the form `2. SOMETHING IN CAPS` is a section heading;
 * - a block of `•` bullets becomes one node per bullet, since a fifteen-item list read as one
 *   utterance is unusable.
 */
@Composable
fun LegalBody(
    text: String,
    fontSize: TextUnit = 16.sp,
    modifier: Modifier = Modifier
) {
    val blocks = remember(text) { splitIntoBlocks(text) }
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is LegalBlock.Heading -> Text(
                    text = block.text,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp, bottom = 6.dp)
                        .semantics { heading() }
                )

                is LegalBlock.Bullet -> Text(
                    text = block.text,
                    fontSize = fontSize,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                is LegalBlock.Paragraph -> Text(
                    text = block.text,
                    fontSize = fontSize,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )
            }
        }
    }
}

private sealed interface LegalBlock {
    val text: String

    data class Heading(override val text: String) : LegalBlock
    data class Paragraph(override val text: String) : LegalBlock
    data class Bullet(override val text: String) : LegalBlock
}

private val HEADING_PATTERN = Regex("""^\d+\.\s+\S.*""")

private fun splitIntoBlocks(text: String): List<LegalBlock> {
    val blocks = mutableListOf<LegalBlock>()
    for (raw in text.trim().split(Regex("\\n\\s*\\n"))) {
        val block = raw.trim()
        if (block.isEmpty()) continue

        val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
        when {
            // "• ..." lists: one node per item.
            lines.all { it.startsWith("•") } ->
                lines.forEach { blocks.add(LegalBlock.Bullet(it)) }

            // A lone numbered line, or an all-caps line, is a section title.
            lines.size == 1 && (HEADING_PATTERN.matches(lines[0]) || isAllCapsTitle(lines[0])) ->
                blocks.add(LegalBlock.Heading(lines[0]))

            else -> blocks.add(LegalBlock.Paragraph(lines.joinToString(" ")))
        }
    }
    return blocks
}

/** An all-caps line short enough to be a title, e.g. "SHORT VERSION" or "YOUR RIGHTS" in the
 *  privacy policy, which are written without a leading number. */
private fun isAllCapsTitle(line: String): Boolean =
    line.length <= 60 &&
        line.any { it.isLetter() } &&
        line.filter { it.isLetter() }.all { it.isUpperCase() }
