package com.elvischang.ailinebot.factcheck;

import com.elvischang.ailinebot.store.Material;

public interface FactCheckService {

    // material is null when the question was asked without quoting anything; conversation is the recent group chat.
    FactCheckReply verify(String groupId, Material material, String userQuestion, String conversation);

    default FactCheckReply verify(String groupId, Material material, String userQuestion) {
        return verify(groupId, material, userQuestion, null);
    }
}
