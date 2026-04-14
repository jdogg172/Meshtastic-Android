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

package org.meshtastic.feature.map

import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.Node
import org.meshtastic.proto.Position
import org.meshtastic.proto.Waypoint

/** Scale factor to convert protobuf integer coordinates to decimal degrees. */
internal const val COORDINATE_SCALE = 1e-7

/**
 * Converts a protobuf [Position] integer coordinate pair to a Mapbox [Point], or returns null if the position is
 * zero/missing.
 */
internal fun Position.toPointOrNull(): Point? {
    val lat = (latitude_i ?: 0) * COORDINATE_SCALE
    val lng = (longitude_i ?: 0) * COORDINATE_SCALE
    return if (lat == 0.0 && lng == 0.0) null else Point.fromLngLat(lng, lat)
}

/** Converts an integer colour value to a hex colour string (#RRGGBB). */
internal fun Int.toHexColorString(): String = "#%06X".format(this and 0xFFFFFF)

/** Converts a Unicode code point to its emoji string, falling back to 📍 on error. */
internal fun convertIntToEmoji(codePoint: Int): String = try {
    String(Character.toChars(codePoint))
} catch (_: IllegalArgumentException) {
    "\uD83D\uDCCD"
}

/**
 * Returns the precision radius in metres for a given precision-bits value. Formula mirrors the existing
 * [precisionBitsToMeters] used in core:ui.
 */
@Suppress("MagicNumber")
internal fun precisionBitsToMeters(precisionBits: Int): Double = when {
    precisionBits <= 0 -> 0.0
    precisionBits >= 32 -> 0.0
    else -> 111_320.0 / (1 shl precisionBits) * 180.0
}

// ---------------------------------------------------------------------------
// Node features
// ---------------------------------------------------------------------------

/**
 * Builds a Mapbox [Feature] for a single [Node].
 *
 * Properties carried in the feature:
 * - `nodeNum` — Int, the node number (used as unique id)
 * - `shortName` — String, short display name
 * - `longName` — String, full display name
 * - `color` — String, hex colour for the marker fill
 * - `isFavorite` — Boolean
 * - `lastHeard` — Long, epoch seconds
 * - `precisionMeters` — Double, precision circle radius (0 = no circle)
 */
internal fun Node.toFeature(): Feature? {
    val point = position?.toPointOrNull() ?: return null
    return Feature.fromGeometry(point).also { f ->
        f.addNumberProperty("nodeNum", num)
        f.addStringProperty("shortName", user?.short_name ?: "?")
        f.addStringProperty("longName", user?.long_name ?: "Unknown")
        f.addStringProperty("color", colors.second.toHexColorString())
        f.addBooleanProperty("isFavorite", isFavorite)
        f.addNumberProperty("lastHeard", lastHeard.toLong())
        f.addNumberProperty("precisionMeters", precisionBitsToMeters(position?.precision_bits ?: 0))
    }
}

/** Converts a list of nodes to a [FeatureCollection] for use with a GeoJSON source. */
internal fun List<Node>.toFeatureCollection(): FeatureCollection =
    FeatureCollection.fromFeatures(mapNotNull { it.toFeature() })

// ---------------------------------------------------------------------------
// Waypoint features
// ---------------------------------------------------------------------------

/**
 * Builds a Mapbox [Feature] for a [Waypoint].
 *
 * Properties:
 * - `id` — Int, waypoint id
 * - `name` — String
 * - `description` — String
 * - `icon` — String, emoji representation
 * - `lockedTo` — Int, node num that locked this waypoint (0 = public)
 */
internal fun Waypoint.toFeature(): Feature? {
    val lat = (latitude_i ?: 0) * COORDINATE_SCALE
    val lng = (longitude_i ?: 0) * COORDINATE_SCALE
    if (lat == 0.0 && lng == 0.0) return null
    return Feature.fromGeometry(Point.fromLngLat(lng, lat)).also { f ->
        f.addNumberProperty("id", id)
        f.addStringProperty("name", name ?: "")
        f.addStringProperty("description", description ?: "")
        f.addStringProperty("icon", convertIntToEmoji(icon ?: 0x1F4CD))
        f.addNumberProperty("lockedTo", locked_to ?: 0)
    }
}

/** Converts a collection of [DataPacket]s (waypoints) to a [FeatureCollection]. */
internal fun Collection<DataPacket>.waypointsToFeatureCollection(): FeatureCollection =
    FeatureCollection.fromFeatures(mapNotNull { it.waypoint?.toFeature() })

// ---------------------------------------------------------------------------
// Track / traceroute geometry
// ---------------------------------------------------------------------------

/** Converts a list of [Position]s to a Mapbox [LineString], returning null if fewer than 2 points. */
internal fun List<Position>.toLineStringOrNull(): LineString? {
    val points = mapNotNull { it.toPointOrNull() }
    return if (points.size >= 2) LineString.fromLngLats(points) else null
}
