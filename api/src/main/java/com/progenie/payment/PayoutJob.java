package com.progenie.payment;

import com.progenie.shared.jobs.JobLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Every Monday 06:00 (business time zone): create payouts for the week that ended on Sunday. */
@Component
class PayoutJob {

    private final PayoutService payouts;
    private final JobLock lock;

    PayoutJob(PayoutService payouts, JobLock lock) {
        this.payouts = payouts;
        this.lock = lock;
    }

    @Scheduled(cron = "0 0 6 * * MON", zone = "${progenie.timezone}")
    @Transactional
    public void weekly() {
        if (lock.tryAcquire("weekly-payouts")) {
            payouts.generate(payouts.lastWeekEnd());
        }
    }
}
