package com.costpilot.analytics.dto;

import java.time.Instant;

// One time bucket in a spend trend (hourly or daily).
public record TrendPoint(Instant bucket, String costUsd, long requests) {
}
