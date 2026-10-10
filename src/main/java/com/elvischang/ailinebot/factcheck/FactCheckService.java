package com.elvischang.ailinebot.factcheck;

import com.elvischang.ailinebot.store.Material;

public interface FactCheckService {

    // material is null when the question was asked without quoting anything.
    FactCheckReply verify(String groupId, Material material, String userQuestion);
}
