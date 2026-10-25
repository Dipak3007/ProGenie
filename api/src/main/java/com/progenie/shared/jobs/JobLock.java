package com.progenie.shared.jobs;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.util.QueryUtil;

/**
 * Makes a scheduled job run on only one API replica at a time, using a PostgreSQL
 * transaction-level advisory lock (released automatically at commit/rollback).
 * No extra infrastructure (ShedLock, Redis) is needed.
 */
@Component
public class JobLock {

    private final JdbcClient jdbc;

    public JobLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Must be called inside the job's transaction. Returns false if another replica holds the lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryAcquire(String jobName) {
        Boolean acquired = jdbc.sql(QueryUtil.get(QueryConstants.LOCK_JOB_ADVISORY))
            .param("name", "progenie-job:" + jobName)
            .query(Boolean.class)
            .single();
        return Boolean.TRUE.equals(acquired);
    }
}
