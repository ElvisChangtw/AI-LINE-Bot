package com.elvischang.ailinebot.job;

import com.elvischang.ailinebot.media.MediaStore;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class JobEventListener {

    private final JobRunner runner;
    private final MediaStore mediaStore;

    public JobEventListener(JobRunner runner, MediaStore mediaStore) {
        this.runner = runner;
        this.mediaStore = mediaStore;
    }

    @Async("jobExecutor")
    @TransactionalEventListener(fallbackExecution = true)
    public void onJobReady(JobReadyEvent event) {
        runner.run(event.jobId());
    }

    @Async("mediaExecutor")
    @TransactionalEventListener(fallbackExecution = true)
    public void onImageReceived(ImageReceivedEvent event) {
        mediaStore.download(event.groupId(), event.messageId());
    }
}
