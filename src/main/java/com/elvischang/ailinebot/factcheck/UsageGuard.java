package com.elvischang.ailinebot.factcheck;

import com.elvischang.ailinebot.store.JobRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Component
public class UsageGuard {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final JobRepository jobs;
    private final FactCheckProperties props;

    public UsageGuard(JobRepository jobs, FactCheckProperties props) {
        this.jobs = jobs;
        this.props = props;
    }

    public boolean allows(long now) {
        ZonedDateTime local = Instant.ofEpochMilli(now).atZone(TAIPEI);
        long startOfDay = local.toLocalDate().atStartOfDay(TAIPEI).toInstant().toEpochMilli();
        long startOfMonth = local.toLocalDate().withDayOfMonth(1).atStartOfDay(TAIPEI).toInstant().toEpochMilli();
        return jobs.sumCostSince(startOfDay) < props.dailyLimitMicroUsd()
                && jobs.sumCostSince(startOfMonth) < props.monthlyLimitMicroUsd();
    }
}
