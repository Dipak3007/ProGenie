package com.progenie.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class DocumentNumbersTest {

    @Test
    void financialYearRunsAprilToMarch() {
        assertThat(DocumentNumbers.fiscalYear(LocalDate.of(2026, 9, 27))).isEqualTo("2026-27");
        assertThat(DocumentNumbers.fiscalYear(LocalDate.of(2027, 3, 31))).isEqualTo("2026-27");
        assertThat(DocumentNumbers.fiscalYear(LocalDate.of(2027, 4, 1))).isEqualTo("2027-28");
        assertThat(DocumentNumbers.fiscalYear(LocalDate.of(2099, 5, 1))).isEqualTo("2099-00");
    }

    @Test
    void numbersArePrefixedPerSeries() {
        assertThat(DocumentNumbers.format(DocumentNumbers.Series.RECEIPT, "2026-27", 123)).isEqualTo("PG/2026-27/000123");
        assertThat(DocumentNumbers.format(DocumentNumbers.Series.CREDIT_NOTE, "2026-27", 4)).isEqualTo("PG-CN/2026-27/000004");
    }
}
