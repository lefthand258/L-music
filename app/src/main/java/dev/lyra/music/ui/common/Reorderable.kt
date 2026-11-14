package dev.lyra.music.ui.common

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Minimal long-press drag-to-reorder for a LazyColumn. Items must be keyed; [onMove] is called
 * every time the dragged item crosses a neighbour, and [onDrop] once when it is released.
 * Only the item being dragged is drawn displaced, so the rest of the list stays cheap.
 */
class ReorderState internal constructor(
    val listState: LazyListState,
    private val scope: CoroutineScope,
) {
    // Refreshed on every composition so they always see the latest list.
    internal var keyOf: (Int) -> Any? = { null }
    internal var onMove: (from: Int, to: Int) -> Unit = { _, _ -> }
    internal var onDrop: () -> Unit = {}

    var draggingKey by mutableStateOf<Any?>(null)
        private set
    var dragOffset by mutableFloatStateOf(0f)
        private set
    private var draggingIndex = -1
    private var autoScroll: Job? = null

    private fun itemAt(index: Int): LazyListItemInfo? = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }

    internal fun start(index: Int) {
        draggingIndex = index
        draggingKey = keyOf(index)
        dragOffset = 0f
    }

    internal fun drag(delta: Float) {
        if (draggingIndex < 0) return
        dragOffset += delta
        val item = itemAt(draggingIndex) ?: return
        val centre = item.offset + dragOffset + item.size / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { other ->
            other.index != draggingIndex && centre > other.offset && centre < other.offset + other.size
        }
        if (target != null) {
            onMove(draggingIndex, target.index)
            // The dragged item now sits where the target was: keep it under the finger.
            dragOffset += item.offset - target.offset
            draggingIndex = target.index
            draggingKey = keyOf(target.index)
        }
        val info = listState.layoutInfo
        val top = item.offset + dragOffset
        val bottom = top + item.size
        val edge = item.size.toFloat()
        val speed = when {
            top < info.viewportStartOffset + edge -> -((info.viewportStartOffset + edge - top) / edge * 20f)
            bottom > info.viewportEndOffset - edge -> (bottom - (info.viewportEndOffset - edge)) / edge * 20f
            else -> 0f
        }
        if (speed == 0f) { autoScroll?.cancel(); autoScroll = null }
        else if (autoScroll?.isActive != true) {
            autoScroll = scope.launch {
                while (isActive && draggingIndex >= 0) {
                    listState.dispatchRawDelta(speed)
                    dragOffset -= speed
                    delay(16)
                }
            }
        }
    }

    internal fun end() {
        autoScroll?.cancel(); autoScroll = null
        if (draggingIndex >= 0) onDrop()
        draggingIndex = -1
        draggingKey = null
        dragOffset = 0f
    }
}

@Composable
fun rememberReorderState(
    listState: LazyListState,
    keyOf: (Int) -> Any?,
    onMove: (from: Int, to: Int) -> Unit,
    onDrop: () -> Unit = {},
): ReorderState {
    val scope = rememberCoroutineScope()
    val state = remember(listState) { ReorderState(listState, scope) }
    state.keyOf = keyOf
    state.onMove = onMove
    state.onDrop = onDrop
    return state
}

/** Put on the whole row: long-press anywhere on it starts the drag. */
fun Modifier.reorderable(state: ReorderState, index: Int): Modifier = pointerInput(state, index) {
    detectDragGesturesAfterLongPress(
        onDragStart = { state.start(index) },
        onDrag = { change, delta -> change.consume(); state.drag(delta.y) },
        onDragEnd = { state.end() },
        onDragCancel = { state.end() },
    )
}

/** Visual displacement of the item being dragged. */
fun Modifier.reorderItem(state: ReorderState, key: Any?): Modifier {
    if (state.draggingKey != key) return this
    return this
        .zIndex(1f)
        .graphicsLayer {
            translationY = state.dragOffset
            scaleX = 1.02f; scaleY = 1.02f
            shadowElevation = 12f
        }
}
