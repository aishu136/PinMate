package org.example.agent.brand;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.example.agent.model.Brand;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** A saved brand voice. */
@Entity
@Table(name = "brand_profile")
public class BrandProfile extends PanacheEntity {

    @Column(nullable = false, length = 100)
    public String name;
    @Column(length = 200)
    public String audience;
    @Column(length = 500)
    public String voice;
    @Column(length = 2000)
    public String guidelines;
    /** One per line. */
    @Column(length = 3000)
    public String bannedWords;
    /** Space-separated. */
    @Column(length = 500)
    public String requiredHashtags;

    @Column(nullable = false)
    public Instant createdAt;
    @Column(nullable = false)
    public Instant updatedAt;

    Brand toBrand() {
        return new Brand(name, audience, voice, guidelines, split(bannedWords, "\n"), split(requiredHashtags, " "));
    }

    static List<String> split(String joined, String separator) {
        return joined == null || joined.isEmpty() ? List.of() : Arrays.asList(joined.split(separator));
    }
}
