package com.logguardian.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UtilsTest {

    @Test
    void detectsJsonSafely() {
        assertThat(Utils.checkIfJson(null)).isFalse();
        assertThat(Utils.checkIfJson(" not-json ")).isFalse();
        assertThat(Utils.checkIfJson(" {\"level\":\"ERROR\"} ")).isTrue();
    }
}
