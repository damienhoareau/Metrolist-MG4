/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.utils

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// The MG4 head unit draws its own bars over apps without reporting them as insets. Sizes are in
// physical pixels: the top bar was measured by the DiAuto-MG4 project (OemDisplayChrome.TOP_BAR_PX),
// the left dock is SystemUI's dock_bar_width (the DockBar window anchored bottom-left).
const val HEAD_UNIT_TOP_BAR_PX = 80
const val HEAD_UNIT_LEFT_BAR_PX = 142

/**
 * A [Dialog] whose content stays clear of the head unit bars. Dialogs are separate windows, so the
 * padding applied to the app's root does not reach them.
 */
@Composable
fun HeadUnitDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    includeTopInset: Boolean = true,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        val insets =
            with(LocalDensity.current) {
                PaddingValues(
                    start = HEAD_UNIT_LEFT_BAR_PX.toDp(),
                    top = if (includeTopInset) HEAD_UNIT_TOP_BAR_PX.toDp() else 0.dp,
                )
            }
        Box(Modifier.padding(insets)) { content() }
    }
}
