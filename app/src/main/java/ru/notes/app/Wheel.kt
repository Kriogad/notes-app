package ru.notes.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ITEM = 48.dp
private const val VISIBLE = 5 // выбранное значение — в середине из пяти строк
private const val LOOPS = 2000 // «бесконечная» прокрутка: после 59 снова 00

/** Барабан с цифрами, как в будильнике. */
@Composable
fun Wheel(count: Int, initial: Int, onSelect: (Int) -> Unit) {
    val start = LOOPS / 2 * count + initial - VISIBLE / 2
    val state = rememberLazyListState(start)
    val itemPx = with(LocalDensity.current) { ITEM.toPx() }
    val center by remember {
        derivedStateOf {
            val first = state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > itemPx / 2) 1 else 0
            first + VISIBLE / 2
        }
    }
    LaunchedEffect(state) { snapshotFlow { center }.collect { onSelect(it % count) } }

    Box(Modifier.width(80.dp).height(ITEM * VISIBLE), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxWidth().height(ITEM)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
        )
        LazyColumn(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
            modifier = Modifier.fillMaxWidth().height(ITEM * VISIBLE),
        ) {
            items(count * LOOPS) { i ->
                val selected = i == center
                Box(Modifier.fillMaxWidth().height(ITEM), contentAlignment = Alignment.Center) {
                    Text(
                        "%02d".format(i % count),
                        fontSize = if (selected) 30.sp else 22.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}
