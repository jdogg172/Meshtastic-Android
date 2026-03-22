/*
 * Copyright (c) 2025-2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.core.ui.util

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Conditionally applies the [action] to the receiver [Modifier] if [precondition] is true. Otherwise, returns the
 * receiver unchanged.
 */
inline fun Modifier.thenIf(precondition: Boolean, action: Modifier.() -> Modifier): Modifier =
    if (precondition) action() else this

/**
 * Adds a secondary (right) mouse-button click handler. On touch-only platforms the secondary button event never fires,
 * so this is a safe no-op. Intended to mirror `onLongClick` behavior for desktop users who expect right-click context
 * actions.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.onRightClick(action: () -> Unit): Modifier = pointerInput(action) {
    awaitEachGesture {
        val event = awaitPointerEvent()
        if (event.type == PointerEventType.Press && event.button == PointerButton.Secondary) {
            action()
        }
    }
}
