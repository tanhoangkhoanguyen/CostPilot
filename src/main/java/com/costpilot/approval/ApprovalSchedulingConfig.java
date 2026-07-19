package com.costpilot.approval;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Enables the @Scheduled TTL sweep (8.2). Scoped to this config so scheduling is turned
// on only for the approval-expiry job, not implicitly app-wide behavior elsewhere.
@Configuration
@EnableScheduling
class ApprovalSchedulingConfig {
}
