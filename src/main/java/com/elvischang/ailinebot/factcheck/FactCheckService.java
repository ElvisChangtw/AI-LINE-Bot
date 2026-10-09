package com.elvischang.ailinebot.factcheck;

import com.elvischang.ailinebot.store.Material;

public interface FactCheckService {

    String verify(Material material, String userQuestion);
}
