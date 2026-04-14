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
@file:Suppress("MagicNumber")

package org.meshtastic.feature.map.node

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapbox.geojson.Point
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.style.MapStyle
import org.meshtastic.core.model.util.GeoConstants.DEG_D
import org.meshtastic.core.ui.component.MainAppBar
import org.meshtastic.feature.map.TrackPolyline

/**
 * Full-screen Mapbox node track map with [MainAppBar].
 *
 * Driven by [nodeMapViewModel] which provides the node and its position log.
 */
@OptIn(MapboxExperimental::class)
@Composable
fun NodeMapScreen(nodeMapViewModel: NodeMapViewModel, onNavigateUp: () -> Unit) {
    val node by nodeMapViewModel.node.collectAsStateWithLifecycle()
    val positions by nodeMapViewModel.positionLogs.collectAsStateWithLifecycle()

    val mostRecentPos = positions.maxByOrNull { it.time }
    val initLat = (mostRecentPos?.latitude_i ?: 0) * DEG_D
    val initLng = (mostRecentPos?.longitude_i ?: 0) * DEG_D

    val viewportState = rememberMapViewportState {
        setCameraOptions {
            center(Point.fromLngLat(if (initLng != 0.0) initLng else 0.0, if (initLat != 0.0) initLat else 0.0))
            zoom(if (initLat != 0.0 || initLng != 0.0) 12.0 else 2.0)
        }
    }

    val colorInt = node?.colors?.second ?: android.graphics.Color.parseColor("#2196F3")

    Scaffold(
        topBar = {
            MainAppBar(
                title = node?.user?.long_name ?: "",
                ourNode = null,
                showNodeChip = false,
                canNavigateUp = true,
                onNavigateUp = onNavigateUp,
                actions = {},
                onClickChip = {},
            )
        },
    ) { paddingValues ->
        MapboxMap(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            mapViewportState = viewportState,
            style = { MapStyle(style = com.mapbox.maps.Style.STANDARD) },
        ) {
            TrackPolyline(positions = positions, colorInt = colorInt)
        }
    }
}
