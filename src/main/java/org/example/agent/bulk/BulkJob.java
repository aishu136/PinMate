package org.example.agent.bulk;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/** A set of pages to write pins for, with the options shared by all of them. */
@Entity
@Table(name = "bulk_job")
public class BulkJob extends PanacheEntity {

    /** The sitemap or feed the URLs came from, or null for a pasted list. */
    @Column(length = 2048)
    public String source;
    @Column(length = 200)
    public String audience;
    @Column(length = 50)
    public String tone;
    public int variations;

    @Column(nullable = false)
    public Instant createdAt;
}
