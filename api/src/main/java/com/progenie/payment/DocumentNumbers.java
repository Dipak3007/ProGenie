package com.progenie.payment;

import java.time.LocalDate;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gap-free document numbers per series and Indian financial year (April–March), e.g. PG/2026-27/000123.
 * The counter row stays locked until the caller's transaction ends, so a rolled-back receipt gives its number
 * back and two payments can never get the same one.
 */
@Component
class DocumentNumbers {

    enum Series {
        RECEIPT("PG"), CREDIT_NOTE("PG-CN");

        final String prefix;

        Series(String prefix) {
            this.prefix = prefix;
        }
    }

    private final JdbcClient jdbc;

    DocumentNumbers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    String next(Series series, LocalDate date) {
        String fy = fiscalYear(date);
        long value = jdbc.sql("""
                INSERT INTO document_sequences (series, fiscal_year, next_value) VALUES (:s, :fy, 2)
                ON CONFLICT (series, fiscal_year) DO UPDATE SET next_value = document_sequences.next_value + 1
                RETURNING next_value - 1
                """)
            .param("s", series.name()).param("fy", fy)
            .query(Long.class).single();
        return format(series, fy, value);
    }

    static String format(DocumentNumbers.Series series, String fiscalYear, long value) {
        return "%s/%s/%06d".formatted(series.prefix, fiscalYear, value);
    }

    /** 2026-09-27 → "2026-27"; 2027-02-01 → "2026-27"; 2027-04-01 → "2027-28". */
    static String fiscalYear(LocalDate date) {
        int start = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        return "%d-%02d".formatted(start, (start + 1) % 100);
    }
}
