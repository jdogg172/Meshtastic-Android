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
@file:Suppress("MagicNumber", "UnusedParameter")

package org.meshtastic.feature.map.node

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapbox.geojson.Point
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.style.MapStyle
import org.koin.compose.viewmodel.koinViewModel
import org.meshtastic.core.model.util.GeoConstants.DEG_D
import org.meshtastic.feature.map.TrackPolyline
import org.meshtastic.proto.Position

/**
 * Embeddable Mapbox track map for a single node's position history.
 *
 * Renders a polyline of [positions] filtered by the ViewModel's last-heard track filter. Supports optional synchronized
 * selection: when [selectedPositionTime] is non-null the map animates to the corresponding point; tapping a marker
 * invokes [onPositionSelected].
 */
@OptIn(MapboxExperimental::class)
@Composable
fun NodeTrackMap(
    destNum: Int,
    positions: List<Position>,
    modifier: Modifier = Modifier,
    selectedPositionTime: Int? = null,
    onPositionSelected: ((Int) -> Unit)? = null,
) {
    val vm = koinViewModel<NodeMapViewModel>()
    vm.setDestNum(destNum)
    val focusedNode by vm.node.collectAsStateWithLifecycle()

    // Derive initial camera center from most-recent position or fallback to (0,0)
    val mostRecentPos = positions.maxByOrNull { it.time }
    val initLat = (mostRecentPos?.latitude_i ?: 0) * DEG_D
    val initLng = (mostRecentPos?.longitude_i ?: 0) * DEG_D

    val viewportState = rememberMapViewportState {
        setCameraOptions {
            center(Point.fromLngLat(if (initLng != 0.0) initLng else 0.0, if (initLat != 0.0) initLat else 0.0))
            zoom(if (initLat != 0.0 || initLng != 0.0) 12.0 else 2.0)
        }
    }

    // Animate to selected position when driven from the list
    androidx.compose.runtime.LaunchedEffect(selectedPositionTime) {
        val selectedTime = selectedPositionTime ?: return@LaunchedEffect
        val pos = positions.find { it.time == selectedTime } ?: return@LaunchedEffect
        val lat = (pos.latitude_i ?: 0) * DEG_D
        val lng = (pos.longitude_i ?: 0) * DEG_D
        if (lat != 0.0 || lng != 0.0) {
            viewportState.flyTo(com.mapbox.maps.CameraOptions.Builder().center(Point.fromLngLat(lng, lat)).build())
        }
    }

    val colorInt = focusedNode?.colors?.second ?: android.graphics.Color.parseColor("#2196F3")

    MapboxMap(
        modifier = modifier,
        mapViewportState = viewportState,
        style = { MapStyle(style = com.mapbox.maps.Style.STANDARD) },
    ) {
        TrackPolyline(positions = positions, colorInt = colorInt)
    }
}
