package io.github.dglearner.opsagent.stream.baseline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Log templates, learned online with Drain (He et al., "Drain: An Online Log Parsing Approach with Fixed Depth Tree",
 * ICWS 2017). A message is split into tokens; tokens that are evidently values (ids, numbers, addresses) are masked
 * first. The tree then narrows the search: by the number of tokens, then by the first {@link #PREFIX} tokens (a token
 * with a digit goes under a wildcard child, since it is probably a value). Among the templates at that leaf the most
 * similar one is taken if at least {@code similarity} of its tokens are equal, and the positions that differ become
 * {@code <*>}; otherwise the message starts a new template.
 *
 * <p>A template keeps the id it was given when it was created, so its counts and baseline survive its getting more
 * general. The model is plain data ({@link #templates()}, {@link #restore}) to be kept in a state store; the tree is
 * rebuilt from the templates.
 */
public final class Drain {

    public static final String WILDCARD = "<*>";
    /** Tokens of the tree's path below the length; Drain's depth 4 is root, length, two tokens. */
    static final int PREFIX = 2;
    /** Longer messages are cut: their tail rarely tells templates apart, and it costs. */
    static final int MAX_TOKENS = 40;

    private static final Pattern SPLIT = Pattern.compile("[\\s,;=]+");
    private static final List<Pattern> VALUES = List.of(
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
            Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?"),
            Pattern.compile("(0x)?[0-9a-fA-F]*\\d[0-9a-fA-F]*[a-fA-F][0-9a-fA-F]*|(0x)?[0-9a-fA-F]*[a-fA-F][0-9a-fA-F]*"
                    + "\\d[0-9a-fA-F]*"),
            Pattern.compile("[-+]?\\d+([.:]\\d+)*(ms|s|m|h|%|b|kb|mb|gb)?", Pattern.CASE_INSENSITIVE));

    /** A learned template: its stable id, its tokens with {@code <*>} where the messages differ. */
    public record Template(String id, List<String> tokens) {

        public String text() {
            return String.join(" ", tokens);
        }
    }

    private final double similarity;
    private final int maxTemplates;
    private final IdSource ids;
    private final List<Template> templates = new ArrayList<>();
    /** length → first token → second token → templates. */
    private final Map<Integer, Map<String, Map<String, List<Integer>>>> tree = new HashMap<>();

    /** Names a new template from the tokens it starts with; called once per template. */
    @FunctionalInterface
    public interface IdSource {
        String idFor(List<String> tokens);
    }

    public Drain(double similarity, int maxTemplates, IdSource ids) {
        this.similarity = similarity;
        this.maxTemplates = maxTemplates;
        this.ids = ids;
    }

    /** The template the message belongs to, created if none is similar enough; null once the model is full. */
    public Template add(String message) {
        List<String> tokens = tokens(message);
        List<Integer> leaf = leaf(tokens, true);
        int best = -1;
        double bestScore = -1;
        int bestWildcards = -1;
        for (int index : leaf) {
            List<String> t = templates.get(index).tokens();
            int equal = 0;
            int wildcards = 0;
            for (int i = 0; i < t.size(); i++) {
                if (t.get(i).equals(WILDCARD)) {
                    wildcards++;
                } else if (t.get(i).equals(tokens.get(i))) {
                    equal++;
                }
            }
            double score = (double) equal / t.size();
            // Ties go to the template with more wildcards: the more general one, as in Drain.
            if (score > bestScore || score == bestScore && wildcards > bestWildcards) {
                best = index;
                bestScore = score;
                bestWildcards = wildcards;
            }
        }
        if (best >= 0 && bestScore >= similarity) {
            Template current = templates.get(best);
            List<String> merged = merge(current.tokens(), tokens);
            if (!merged.equals(current.tokens())) {
                current = new Template(current.id(), merged);
                templates.set(best, current);
            }
            return current;
        }
        if (templates.size() >= maxTemplates) {
            return null;
        }
        Template created = new Template(ids.idFor(tokens), List.copyOf(tokens));
        templates.add(created);
        leaf.add(templates.size() - 1);
        return created;
    }

    public List<Template> templates() {
        return List.copyOf(templates);
    }

    public static Drain restore(List<Template> templates, double similarity, int maxTemplates, IdSource ids) {
        Drain drain = new Drain(similarity, maxTemplates, ids);
        for (Template t : templates) {
            drain.templates.add(t);
            drain.leaf(t.tokens(), true).add(drain.templates.size() - 1);
        }
        return drain;
    }

    /** The message's tokens with the evident values masked, at most {@link #MAX_TOKENS}. */
    static List<String> tokens(String message) {
        String[] raw = SPLIT.split(message == null ? "" : message.strip());
        List<String> tokens = new ArrayList<>(Math.min(raw.length, MAX_TOKENS));
        for (String token : Arrays.copyOf(raw, Math.min(raw.length, MAX_TOKENS))) {
            if (token.isEmpty()) {
                continue;
            }
            String bare = token.replaceAll("^[\"'(\\[]+|[\"')\\].:]+$", "");
            tokens.add(isValue(bare) ? WILDCARD : token);
        }
        if (tokens.isEmpty()) {
            tokens.add(WILDCARD);
        }
        return tokens;
    }

    private static boolean isValue(String token) {
        if (token.isEmpty()) {
            return false;
        }
        for (Pattern p : VALUES) {
            if (p.matcher(token).matches()) {
                return true;
            }
        }
        return false;
    }

    private List<Integer> leaf(List<String> tokens, boolean create) {
        Map<String, Map<String, List<Integer>>> byFirst = tree.computeIfAbsent(tokens.size(), n -> new HashMap<>());
        Map<String, List<Integer>> bySecond = byFirst.computeIfAbsent(key(tokens, 0), k -> new HashMap<>());
        return bySecond.computeIfAbsent(key(tokens, 1), k -> new ArrayList<>());
    }

    /** A path token; one with a digit or a mask goes under the wildcard, since it is probably a value. */
    private static String key(List<String> tokens, int i) {
        if (i >= tokens.size()) {
            return "";
        }
        String t = tokens.get(i);
        return t.equals(WILDCARD) || t.chars().anyMatch(Character::isDigit) ? WILDCARD : t;
    }

    private static List<String> merge(List<String> template, List<String> tokens) {
        List<String> merged = new ArrayList<>(template.size());
        for (int i = 0; i < template.size(); i++) {
            merged.add(template.get(i).equals(tokens.get(i)) ? template.get(i) : WILDCARD);
        }
        return merged;
    }
}
