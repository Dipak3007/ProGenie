package com.progenie.payment;

import java.util.List;

import com.progenie.payment.LedgerPostings.Entry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Appends ledger entries. The ledger is append-only: corrections are new entries, never updates. */
@Service
public class LedgerService {

    private final JdbcClient jdbc;

    public LedgerService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Posts a balanced set of entries (debits must equal credits). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void postBalanced(List<Entry> entries) {
        if (LedgerPostings.debits(entries).compareTo(LedgerPostings.credits(entries)) != 0) {
            throw new IllegalStateException("Unbalanced ledger posting: " + entries);
        }
        entries.forEach(e -> insert(e, null));
    }

    /** Posts a balanced refund; every entry points at the refund. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void postRefund(List<Entry> entries, java.util.UUID refundId) {
        if (LedgerPostings.debits(entries).compareTo(LedgerPostings.credits(entries)) != 0) {
            throw new IllegalStateException("Unbalanced refund posting: " + entries);
        }
        entries.forEach(e -> insert(e, refundId));
    }

    /** Posts one entry whose other side is outside the platform's books (a bank transfer). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void postExternal(Entry entry) {
        insert(entry, null);
    }

    private void insert(Entry e, java.util.UUID refundId) {
        jdbc.sql("""
                INSERT INTO ledger_entries (booking_id, genie_id, account, direction, amount, description, payout_id, refund_id)
                VALUES (:booking, :genie, :account, :direction, :amount, :description, :payout, :refund)
                """)
            .param("booking", e.bookingId())
            .param("genie", e.genieId())
            .param("account", e.account())
            .param("direction", e.direction())
            .param("amount", e.amount())
            .param("description", e.description())
            .param("payout", e.payoutId())
            .param("refund", refundId)
            .update();
    }
}
