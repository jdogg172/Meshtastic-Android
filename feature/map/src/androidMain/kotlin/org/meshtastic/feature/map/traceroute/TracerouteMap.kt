/*
 * Copyright (c) 2026 Meshtastic LLC
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
@file:Suppress("MagicNumber")

package org.meshtastic.feature.map.traceroute

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mapbox.geojson.Point
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.style.MapStyle
import org.meshtastic.core.model.TracerouteOverlay
import org.meshtastic.feature.map.TraceroutePolylines
import org.meshtastic.feature.map.toPointOrNull
import org.meshtastic.proto.Position

/**
 * Embeddable Mapbox traceroute map.
 *
 * Renders offset forward (blue) and return (orange) polylines for the given [tracerouteOverlay]. Invokes
 * [onMappableCountChanged] with the number of nodes that have positions.
 */
@OptIn(MapboxExperimental::class)
@Composable
fun TracerouteMap(
    tracerouteOverlay: TracerouteOverlay?,
    tracerouteNodePositions: Map<Int, Position>,
    onMappableCountChanged: (shown: Int, total: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Build forward and return point lists from the overlay and node position map
    val forwardPoints =
        remember(tracerouteOverlay, tracerouteNodePositions) {
            tracerouteOverlay?.forwardRoute?.mapNotNull { nodeNum -> tracerouteNodePositions[nodeNum]?.toPointOrNull() }
                ?: emptyList()
        }
    val returnPoints =
        remember(tracerouteOverlay, tracerouteNodePositions) {
            tracerouteOverlay?.returnRoute?.mapNotNull { nodeNum -> tracerouteNodePositions[nodeNum]?.toPointOrNull() }
                ?: emptyList()
        }

    // Report mappable node counts to the caller
    LaunchedEffect(tracerouteOverlay, forwardPoints, returnPoints) {
        if (tracerouteOverlay != null) {
            val allRouteNums = (tracerouteOverlay.forwardRoute + tracerouteOverlay.returnRoute).distinct()
            val mappable = allRouteNums.count { tracerouteNodePositions.containsKey(it) }
            onMappableCountChanged(mappable, tracerouteOverlay.relatedNodeNums.size)
        }
    }

    // Initial camera: center on the midpoint of all route points
    val allPoints = (forwardPoints + returnPoints).distinct()
    val initCenter = allPoints.firstOrNull() ?: Point.fromLngLat(0.0, 0.0)

    val viewportState = rememberMapViewportState {
        setCameraOptions {
            center(initCenter)
            zoom(if (allPoints.isNotEmpty()) 10.0 else 2.0)
        }
    }

    // Auto-fit camera to all points when they first become available
    var hasCentered by remember { mutableStateOf(false) }
    LaunchedEffect(allPoints) {
        if (hasCentered || allPoints.isEmpty()) return@LaunchedEffect
        val target =
            if (allPoints.size == 1) {
                com.mapbox.maps.CameraOptions.Builder().center(allPoints.first()).zoom(12.0).build()
            } else {
                // Compute a rough center by averaging coords
                val avgLng = allPoints.map { it.longitude() }.average()
                val avgLat = allPoints.map { it.latitude() }.average()
                com.mapbox.maps.CameraOptions.Builder().center(Point.fromLngLat(avgLng, avgLat)).zoom(10.0).build()
            }
        viewportState.flyTo(target)
        hasCentered = true
    }

    MapboxMap(
        modifier = modifier,
        mapViewportState = viewportState,
        style = { MapStyle(style = com.mapbox.maps.Style.STANDARD) },
    ) {
        TraceroutePolylines(forwardPoints = forwardPoints, returnPoints = returnPoints)
    }
}
