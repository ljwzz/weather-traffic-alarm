package com.ljwzz.weathertrafficalarm.backend.domain.port;

import com.ljwzz.weathertrafficalarm.backend.domain.model.CommuteMode;
import com.ljwzz.weathertrafficalarm.backend.domain.model.FallbackReason;
import com.ljwzz.weathertrafficalarm.backend.domain.model.PlaceRef;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RouteProvider {

    RouteEstimate estimate(RouteRequest request);

    record RouteRequest(
        PlaceRef origin,
        PlaceRef destination,
        List<PlaceRef> waypoints,
        CommuteMode mode,
        Instant targetDeparture,
        Instant targetArrival
    ) {}

    record RouteEstimate(
        Duration durationSeconds,
        Instant departureTime,
        Instant arrivalTime,
        String providerName,
        Instant reportTime,
        FallbackReason fallbackReason
    ) {}
}
