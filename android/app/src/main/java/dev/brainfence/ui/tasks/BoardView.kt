package dev.brainfence.ui.tasks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.brainfence.domain.board.BoardHold
import dev.brainfence.domain.board.BoardLayout

// The board is a picture of wood, so it keeps its own colors in both themes.
private val Ink = Color(0xFF1B1B21)
private val Lime = Color(0xFFD7FF1F)
private val Wood = Color(0xFFE8CDA2)
private val WoodEdge = Color(0xFFC9A877)
private val WoodTop = Color(0xFFF1DDBB)
private val SloperEdge = Color(0xFFD9BC8E)
private val Hole = Color(0xFFB4875A)
private val Divider = Color(0xFFD2B285)

/**
 * A hangboard (shared/boards.json) with a step's holds highlighted and marked
 * L / R. With [onPick], tapping a hold picks it (the step editor).
 * shared/CONTRACTS.md (Board holds).
 */
@Composable
fun BoardView(
    board: BoardLayout,
    leftHold: String?,
    rightHold: String?,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 560.dp,
    onPick: ((String) -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer()
    val tap = if (onPick == null) Modifier else Modifier.pointerInput(board, onPick) {
        detectTapGestures { p ->
            val scale = size.width / board.width
            val x = p.x / scale
            val y = p.y / scale
            // Topmost first (holds are drawn after slopers), with a little slop for small holds.
            drawOrder(board).asReversed()
                .firstOrNull { h -> x in (h.x - 12)..(h.x + h.w + 12) && y in (h.y - 12)..(h.y + h.h + 12) }
                ?.let { onPick(it.id) }
        }
    }
    Canvas(
        modifier
            .widthIn(max = maxWidth)
            .fillMaxWidth()
            .aspectRatio(board.width / board.height)
            .semantics { contentDescription = board.name }
            .then(tap),
    ) {
        val s = size.width / board.width
        fun rect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color, stroke: Float = 0f) {
            val topLeft = Offset(x * s, y * s)
            val sz = Size(w * s, h * s)
            val corner = CornerRadius(r * s, r * s)
            if (stroke > 0f) drawRoundRect(color, topLeft, sz, corner, style = Stroke(stroke * s))
            else drawRoundRect(color, topLeft, sz, corner)
        }
        val body = board.body
        rect(body.x, body.y, body.w, body.h, body.r, Wood)
        rect(body.x, body.y, body.w, body.h, body.r, WoodEdge, stroke = 4f)
        drawLine(Divider, Offset((body.x + 10) * s, board.dividerY * s), Offset((body.x + body.w - 10) * s, board.dividerY * s), 4f * s)
        for (h in drawOrder(board)) {
            val on = h.id == leftHold || h.id == rightHold
            val sloper = h.kind == "sloper"
            rect(h.x, h.y, h.w, h.h, h.r, if (on) Lime else if (sloper) WoodTop else Hole)
            if (on) rect(h.x, h.y, h.w, h.h, h.r, Ink, stroke = 8f)
            else if (sloper) rect(h.x, h.y, h.w, h.h, h.r, SloperEdge, stroke = 3f)
        }
        val left = board.holds.find { it.id == leftHold }
        val right = board.holds.find { it.id == rightHold }
        if (left != null && left.id == right?.id) {
            marker(left, "LR", board, s, measurer)
        } else {
            left?.let { marker(it, "L", board, s, measurer) }
            right?.let { marker(it, "R", board, s, measurer) }
        }
    }
}

/** Slopers first: the top pockets sit on the raised centre block. */
private fun drawOrder(board: BoardLayout): List<BoardHold> =
    board.holds.filter { it.kind == "sloper" } + board.holds.filter { it.kind != "sloper" }

/** A pill above the hold: over the board for slopers, in the gap above a row otherwise. */
private fun DrawScope.marker(
    h: BoardHold,
    label: String,
    board: BoardLayout,
    s: Float,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val cx = h.x + h.w / 2
    val cy = if (h.kind == "sloper") board.body.y - 30 else h.y - 34
    val half = if (label.length > 1) 46f else 30f
    drawRoundRect(Ink, Offset((cx - half) * s, (cy - 30) * s), Size(half * 2 * s, 60 * s), CornerRadius(30 * s, 30 * s))
    val text = measurer.measure(
        label,
        TextStyle(color = Lime, fontSize = (36f * s).toSp(), fontWeight = FontWeight.Bold),
    )
    drawText(text, topLeft = Offset(cx * s - text.size.width / 2f, cy * s - text.size.height / 2f))
}
