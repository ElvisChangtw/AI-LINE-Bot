package com.elvischang.ailinebot.line;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HashesTest {

    @Test
    void hashesWithSha256() {
        assertThat(Hashes.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Hashes.shortHash("abc")).isEqualTo("ba7816bf8f01cfea");
    }
}
