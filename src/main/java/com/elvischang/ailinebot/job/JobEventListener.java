package com.elvischang.ailinebot.job;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class JobEventListener {

    private final JobRunner runner;

    public JobEventListener(JobRunner runner) {
        this.runner = runner;
    }

    @Async("jobExecutor")
    @TransactionalEventListener(fallbackExecution = true)
    public void onJobReady(JobReadyEvent event) {
        runner.run(event.jobId());
    }
}
