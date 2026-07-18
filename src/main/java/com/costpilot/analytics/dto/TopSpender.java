package com.costpilot.analytics.dto;

// A top spender for a dimension (team / project / user), ranked by cost.
public record TopSpender(String key, String costUsd, long requests) {
}
