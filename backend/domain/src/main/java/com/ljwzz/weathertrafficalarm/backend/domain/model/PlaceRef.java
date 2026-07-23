package com.ljwzz.weathertrafficalarm.backend.domain.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PlaceRef(
    String poiId,
    @NotBlank String name,
    @NotBlank String displayAddress,
    double longitudeGcj02,
    double latitudeGcj02,
    @NotBlank String adcode,
    @NotBlank String citycode
) {
    public PlaceRef {
        if (longitudeGcj02 < -180 || longitudeGcj02 > 180) {
            throw new IllegalArgumentException("longitude out of range: " + longitudeGcj02);
        }
        if (latitudeGcj02 < -180 || latitudeGcj02 > 180) {
            throw new IllegalArgumentException("latitude out of range: " + latitudeGcj02);
        }
    }
}
