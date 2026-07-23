package com.ljwzz.weathertrafficalarm.backend.domain.port;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Abstraction over system clock for testability.
 */
public interface EvaluationClock {
    Instant now();
    ZoneId systemZone();
}
