package com.ljwzz.weathertrafficalarm.core.model

import kotlinx.serialization.Serializable

@Serializable
data class PlaceRef(
    val poiId: String? = null,
    val name: String,
    val displayAddress: String,
    val longitudeGcj02: Double,
    val latitudeGcj02: Double,
    val adcode: String,
    val citycode: String,
) {
    init {
        require(longitudeGcj02 in -180.0..180.0) { "longitude out of range: $longitudeGcj02" }
        require(latitudeGcj02 in -180.0..180.0) { "latitude out of range: $latitudeGcj02" }
        require(name.isNotBlank()) { "name must not be blank" }
    }

    override fun toString(): String =
        "PlaceRef(name=$name, displayAddress=$displayAddress, poiId=$poiId, adcode=$adcode, citycode=$citycode)"
}
