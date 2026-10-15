package org.example.agent.bulk;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/** One page of a {@link BulkJob} and the pins written for it. */
@Entity
@Table(name = "bulk_item", indexes = {@Index(columnList = "status, job_id, seq"), @Index(columnList = "job_id, seq")})
public class BulkItem extends PanacheEntity {

    public enum Status {
        PENDING,
        /** Claimed by a worker; reset to PENDING if the server stops mid-way. */
        RUNNING,
        DONE,
        FAILED,
        CANCELLED
    }

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    public BulkJob job;
    /** Order within the job. */
    public int seq;

    @Column(nullable = false, length = 2048)
    public String url;
    @Column(nullable = false, length = 300)
    public String topic;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status;

    /** JSON array of {@link org.example.agent.model.PinIdea}. */
    @Column(length = 100_000)
    public String pinsJson;
    /** Whether the page could be read; null until processed. */
    public Boolean pageRead;
    @Column(length = 2000)
    public String pageSummary;
    @Column(length = 100)
    public String model;
    /** Generate passes the agent needed. */
    public int attempts;
    @Column(length = 1000)
    public String error;
    public Instant finishedAt;
}
