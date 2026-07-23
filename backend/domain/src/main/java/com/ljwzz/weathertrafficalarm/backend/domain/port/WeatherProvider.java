package com.ljwzz.weathertrafficalarm.backend.domain.port;

import com.ljwzz.weathertrafficalarm.backend.domain.model.FallbackReason;
import com.ljwzz.weathertrafficalarm.backend.domain.model.PlaceRef;
import java.time.Instant;

public interface WeatherProvider {

    WeatherEstimate estimate(WeatherRequest request);

    record WeatherRequest(
        PlaceRef origin,
        PlaceRef destination,
        Instant windowStart,
        Instant windowEnd,
        String weatherRuleVersion
    ) {}

    record WeatherEstimate(
        int severity,
        int bufferMinutes,
        String weatherRuleVersion,
        String providerName,
        Instant providerReportTime,
        Instant windowStart,
        Instant windowEnd,
        FallbackReason fallbackReason
    ) {}
}
