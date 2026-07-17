package com.diagnostic.agent.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultSensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new DefaultSensitiveDataMasker();

    // ---- 手机号 ----

    @Test
    void shouldMaskPhoneNumber() {
        assertThat(masker.mask("手机号 13812345678 有问题"))
                .isEqualTo("手机号 138****5678 有问题");
    }

    @Test
    void shouldMaskMultiplePhones() {
        assertThat(masker.mask("13812345678 和 13998765432"))
                .isEqualTo("138****5678 和 139****5432");
    }

    // ---- 邮箱 ----

    @Test
    void shouldMaskEmail() {
        assertThat(masker.mask("联系 test@gmail.com"))
                .isEqualTo("联系 t***@gmail.com");
    }

    @Test
    void shouldMaskEmailWithPlusSign() {
        assertThat(masker.mask("邮箱 test+spam@gmail.com"))
                .isEqualTo("邮箱 t***@gmail.com");
    }

    @Test
    void shouldMaskEmailWithDots() {
        assertThat(masker.mask("邮箱 first.last@company.co.uk"))
                .isEqualTo("邮箱 f***@company.co.uk");
    }

    // ---- 身份证 ----

    @Test
    void shouldMaskIdCard() {
        assertThat(masker.mask("身份证 110101199001011234"))
                .isEqualTo("身份证 110101********1234");
    }

    @Test
    void shouldMaskIdCardEndingWithX() {
        assertThat(masker.mask("身份证 11010119900101123X"))
                .isEqualTo("身份证 110101********123X");
    }

    // ---- 边界 ----

    @Test
    void shouldReturnNullForNullInput() {
        assertThat(masker.mask(null)).isNull();
    }

    @Test
    void shouldReturnEmptyForEmptyInput() {
        assertThat(masker.mask("")).isEmpty();
    }

    @Test
    void shouldNotAlterDiagnosticData() {
        String diagData = "CPU usage=92.5% memory=8589934592 bytes shared_buffers=256MB";
        assertThat(masker.mask(diagData)).isEqualTo(diagData);
    }

    @Test
    void shouldNotAlterSqlExplainOutput() {
        String explain = "type=ALL rows=100000 Extra=Using filesort";
        assertThat(masker.mask(explain)).isEqualTo(explain);
    }
}
