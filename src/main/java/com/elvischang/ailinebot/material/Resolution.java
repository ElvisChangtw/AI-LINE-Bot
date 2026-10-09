package com.elvischang.ailinebot.material;

import com.elvischang.ailinebot.store.Material;

public sealed interface Resolution permits Resolution.FactCheck, Resolution.Imagine, Resolution.Notice {

    record FactCheck(Material material) implements Resolution {
    }

    record Imagine(String prompt) implements Resolution {
    }

    record Notice(String text) implements Resolution {
    }
}
