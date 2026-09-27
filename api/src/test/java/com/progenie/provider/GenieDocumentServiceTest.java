package com.progenie.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.progenie.shared.error.ApiException;
import org.junit.jupiter.api.Test;

class GenieDocumentServiceTest {

    @Test
    void detectsFileTypesByTheirFirstBytesNotTheirName() {
        assertThat(GenieDocumentService.sniffContentType("%PDF-1.7".getBytes())).isEqualTo("application/pdf");
        assertThat(GenieDocumentService.sniffContentType(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isEqualTo("image/jpeg");
        assertThat(GenieDocumentService.sniffContentType(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})).isEqualTo("image/png");
        assertThat(GenieDocumentService.sniffContentType("RIFF1234WEBPVP8 ".getBytes())).isEqualTo("image/webp");
        assertThat(GenieDocumentService.sniffContentType("<html>".getBytes())).isNull();
        assertThat(GenieDocumentService.sniffContentType(new byte[0])).isNull();
    }

    @Test
    void storesOnlyAMaskedIdNumber() {
        assertThat(GenieDocumentService.maskNumber("AADHAAR", "1234")).isEqualTo("XXXX-XXXX-1234");
        assertThat(GenieDocumentService.maskNumber("PAN", "123f")).isEqualTo("XXXXXX123F");
        assertThat(GenieDocumentService.maskNumber("SELFIE", null)).isNull();
    }

    @Test
    void idDocumentsNeedTheLastFourCharacters() {
        assertThatThrownBy(() -> GenieDocumentService.maskNumber("AADHAAR", "12"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("last 4");
        assertThatThrownBy(() -> GenieDocumentService.maskNumber("PAN", null)).isInstanceOf(ApiException.class);
    }
}
