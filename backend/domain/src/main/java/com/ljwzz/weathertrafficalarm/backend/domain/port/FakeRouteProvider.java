package com.ljwzz.weathertrafficalarm.backend.domain.port;

import com.ljwzz.weathertrafficalarm.backend.domain.model.FallbackReason;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public class FakeRouteProvider implements RouteProvider {

    private final long fakeDurationSeconds;
    private final boolean shouldFail;

    public FakeRouteProvider() {
        this(1800, false);
    }

    public FakeRouteProvider(long fakeDurationSeconds, boolean shouldFail) {
        this.fakeDurationSeconds = fakeDurationSeconds;
        this.shouldFail = shouldFail;
    }

    @Override
    public RouteEstimate estimate(RouteRequest request) {
        if (shouldFail) {
            return new RouteEstimate(
                Duration.ZERO,
                request.targetArrival(),
                request.targetArrival(),
                "fake",
                Instant.now(),
                FallbackReason.ROUTE_PROVIDER_TIMEOUT
            );
        }
        var departure = request.targetDeparture() != null
            ? request.targetDeparture()
            : request.targetArrival().minusSeconds(fakeDurationSeconds);
        var duration = Duration.ofSeconds(fakeDurationSeconds);
        return new RouteEstimate(
            duration,
            departure,
            departure.plus(duration),
            "fake",
            Instant.now(),
            FallbackReason.NONE
        );
    }
}
