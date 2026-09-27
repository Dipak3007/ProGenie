package com.progenie.shared.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PhonesAndMaskingTest {

    @Test
    void normalisesIndianMobileNumbers() {
        assertThat(Phones.national("9825000005")).isEqualTo("9825000005");
        assertThat(Phones.national("+91 98250-00005")).isEqualTo("9825000005");
        assertThat(Phones.national("919825000005")).isEqualTo("9825000005");
        assertThat(Phones.national("09825000005")).isEqualTo("9825000005");
        assertThat(Phones.national("12345")).isNull();
        assertThat(Phones.national("a@b.c")).isNull();
        assertThat(Phones.e164Digits("9825000005")).isEqualTo("919825000005");
    }

    @Test
    void masksPhonesAndEmails() {
        assertThat(Masking.phone("9825000005")).isEqualTo("98******05");
        assertThat(Masking.email("customer@example.com")).isEqualTo("c*******@example.com");
        assertThat(Masking.email("ab@x.in")).isEqualTo("a***@x.in");
        assertThat(Masking.destination("9825000005")).isEqualTo("98******05");
    }

    @Test
    void sniffsImagesByTheirBytes() {
        assertThat(FileTypes.sniffImage(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isEqualTo("image/jpeg");
        assertThat(FileTypes.sniffImage("%PDF-1.4".getBytes())).isNull();
        assertThat(FileTypes.sniffImage("not an image".getBytes())).isNull();
    }
}
