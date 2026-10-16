package org.example.agent.brand;

import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.example.agent.ApiException;
import org.example.agent.PinWriter;
import org.example.agent.model.Brand;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Saved brand voices, and looking them up for generation. */
@ApplicationScoped
public class BrandService {

    /** A brand as the API returns it. */
    public record View(long id, String name, String audience, String voice, String guidelines,
                       List<String> bannedWords, List<String> requiredHashtags, Instant updatedAt) {

        static View of(BrandProfile p) {
            Brand b = p.toBrand();
            return new View(p.id, b.name(), b.audience(), b.voice(), b.guidelines(), b.bannedWords(),
                    b.requiredHashtags(), p.updatedAt);
        }
    }

    @Transactional
    public List<View> list() {
        return BrandProfile.<BrandProfile>listAll(Sort.by("name")).stream().map(View::of).toList();
    }

    @Transactional
    public View get(long id) {
        return View.of(find(id));
    }

    @Transactional
    public View create(BrandInput input) {
        BrandProfile p = new BrandProfile();
        p.createdAt = Instant.now();
        apply(p, input);
        p.persist();
        return View.of(p);
    }

    @Transactional
    public View update(long id, BrandInput input) {
        BrandProfile p = find(id);
        apply(p, input);
        return View.of(p);
    }

    @Transactional
    public void delete(long id) {
        find(id).delete();
    }

    /** The brand to generate with, or null when {@code id} is null. Unknown ids are a client error. */
    @Transactional
    public Brand resolve(Long id) {
        if (id == null) {
            return null;
        }
        BrandProfile p = BrandProfile.findById(id);
        if (p == null) {
            throw new ApiException(400, "unknown_brand", "No brand with id " + id + ".");
        }
        return p.toBrand();
    }

    /** Like {@link #resolve} but returns null for a brand deleted since the job was queued. */
    @Transactional
    public Brand resolveIfPresent(Long id) {
        BrandProfile p = id == null ? null : BrandProfile.findById(id);
        return p == null ? null : p.toBrand();
    }

    private static void apply(BrandProfile p, BrandInput in) {
        String name = in.name().strip();
        long sameName = BrandProfile.count("lower(name) = ?1 and id <> ?2", name.toLowerCase(Locale.ROOT),
                p.id == null ? -1L : p.id);
        if (sameName > 0) {
            throw new ApiException(409, "duplicate_brand", "A brand named \"" + name + "\" already exists.");
        }
        p.name = name;
        p.audience = blankToNull(in.audience());
        p.voice = blankToNull(in.voice());
        p.guidelines = blankToNull(in.guidelines());
        p.bannedWords = joinOrNull(cleanWords(in.bannedWords()), "\n");
        p.requiredHashtags = joinOrNull(cleanHashtags(in.requiredHashtags()), " ");
        p.updatedAt = Instant.now();
    }

    /** Trimmed, single-spaced and de-duplicated ignoring case. */
    static List<String> cleanWords(List<String> words) {
        if (words == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        return words.stream()
                .map(w -> w.strip().replaceAll("\\s+", " "))
                .filter(w -> !w.isEmpty() && seen.add(w.toLowerCase(Locale.ROOT)))
                .toList();
    }

    static List<String> cleanHashtags(List<String> tags) {
        return tags == null ? List.of() : PinWriter.normalizeHashtags(tags).stream().limit(5).toList();
    }

    private static String joinOrNull(List<String> parts, String separator) {
        return parts.isEmpty() ? null : String.join(separator, parts);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static BrandProfile find(long id) {
        BrandProfile p = BrandProfile.findById(id);
        if (p == null) {
            throw new ApiException(404, "not_found", "No brand with id " + id + ".");
        }
        return p;
    }
}
