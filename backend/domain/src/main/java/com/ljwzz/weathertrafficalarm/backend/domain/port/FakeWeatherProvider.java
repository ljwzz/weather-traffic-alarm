package com.ljwzz.weathertrafficalarm.backend.domain.port;

import com.ljwzz.weathertrafficalarm.backend.domain.model.FallbackReason;
import java.time.Instant;

public class FakeWeatherProvider implements WeatherProvider {

    private final int fakeSeverity;
    private final boolean shouldFail;

    public FakeWeatherProvider() {
        this(0, false);
    }

    public FakeWeatherProvider(int fakeSeverity, boolean shouldFail) {
        this.fakeSeverity = fakeSeverity;
        this.shouldFail = shouldFail;
    }

    @Override
    public WeatherEstimate estimate(WeatherRequest request) {
        if (shouldFail) {
            return new WeatherEstimate(
                0, 0, request.weatherRuleVersion(),
                "fake", Instant.now(),
                request.windowStart(), request.windowEnd(),
                FallbackReason.WEATHER_PROVIDER_TIMEOUT
            );
        }
        return new WeatherEstimate(
            fakeSeverity,
            fakeSeverity > 0 ? fakeSeverity * 10 : 0,
            request.weatherRuleVersion(),
            "fake", Instant.now(),
            request.windowStart(), request.windowEnd(),
            FallbackReason.NONE
        );
    }
}
