package com.elvischang.ailinebot.factcheck;

import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.store.Material;
import org.springframework.stereotype.Service;

@Service
public class PlaceholderFactCheckService implements FactCheckService {

    @Override
    public String verify(Material material, String userQuestion) {
        return BotReplies.FACT_CHECK_NOT_READY;
    }
}
