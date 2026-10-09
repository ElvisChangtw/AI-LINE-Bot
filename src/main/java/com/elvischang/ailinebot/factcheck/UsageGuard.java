package com.elvischang.ailinebot.factcheck;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Component
public class UsageGuard {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final SpendRepository spending;
    private final FactCheckProperties props;

    public UsageGuard(SpendRepository spending, FactCheckProperties props) {
        this.spending = spending;
        this.props = props;
    }

    public boolean allows(long now) {
        ZonedDateTime local = Instant.ofEpochMilli(now).atZone(TAIPEI);
        long startOfDay = local.toLocalDate().atStartOfDay(TAIPEI).toInstant().toEpochMilli();
        long startOfMonth = local.toLocalDate().withDayOfMonth(1).atStartOfDay(TAIPEI).toInstant().toEpochMilli();
        return spending.sumSince(startOfDay) < props.dailyLimitMicroUsd()
                && spending.sumSince(startOfMonth) < props.monthlyLimitMicroUsd();
    }
}
