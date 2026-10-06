package com.nexora.shared.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Why a Clock bean: the whole application agrees on ONE source of time and ONE time zone,
// instead of each class calling Instant.now() against whatever the server's clock/zone is.
// In tests we can replace it with Clock.fixed(...) so time-based rules are predictable.
// NOTE: database timestamps stay in UTC (hibernate.jdbc.time_zone: UTC). The Indian zone is
// only for business-date reasoning, e.g. "what is today's date" for numbering and due dates.
@Configuration // This class contains bean definitions; Spring finds it by scanning com.nexora.
public class ClockConfig {

    // The returned object becomes a Spring bean named "clock".
    // Any class with a "Clock clock" constructor parameter receives this same instance.
    @Bean
    public Clock clock() {
        // Clock.system(zone) = the real system time, interpreted in the given zone.
        return Clock.system(ZoneId.of("Asia/Kolkata"));
    }
}
