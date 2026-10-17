package io.github.dglearner.opsagent.core.agentops;

import io.github.dglearner.opsagent.contracts.AgentResult.Evidence;
import io.github.dglearner.opsagent.contracts.AgentResult.Hypothesis;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps only the evidence an incident really has. A language model can cite an alert or event it never read, or make
 * up an id; such a citation is moved to the rejected list rather than shown as support for the hypothesis.
 */
final class EvidenceCheck {

    /** What the incident has: its id, its alerts, the sample events of those alerts and the catalog's entries. */
    record Known(UUID incidentId, Set<String> alertIds, Set<String> eventIds, Set<String> components) {
    }

    /**
     * @param unsupported some hypothesis was left with no evidence at all: the analysis cannot be taken as certain
     */
    record Checked(List<Hypothesis> hypotheses, List<Evidence> rejected, boolean unsupported) {
    }

    static final int MAX_HYPOTHESES = 5;
    static final int MAX_EVIDENCE = 20;

    static Checked check(List<Hypothesis> hypotheses, Known known) {
        List<Hypothesis> kept = new ArrayList<>();
        List<Evidence> rejected = new ArrayList<>();
        boolean unsupported = false;
        for (Hypothesis hypothesis : hypotheses.stream().limit(MAX_HYPOTHESES).toList()) {
            List<Evidence> valid = new ArrayList<>();
            for (Evidence evidence : hypothesis.evidence().stream().limit(MAX_EVIDENCE).toList()) {
                (known(evidence, known) ? valid : rejected).add(evidence);
            }
            unsupported |= valid.isEmpty();
            kept.add(new Hypothesis(hypothesis.component(), hypothesis.cause(), hypothesis.confidence(), valid));
        }
        return new Checked(kept, rejected, unsupported);
    }

    private static boolean known(Evidence evidence, Known known) {
        if (evidence.kind() == null || evidence.id() == null) {
            return false;
        }
        String id = evidence.id().strip();
        return switch (evidence.kind()) {
            case "alert" -> known.alertIds().contains(id.toLowerCase(Locale.ROOT));
            case "event" -> known.eventIds().contains(id.toLowerCase(Locale.ROOT));
            case "topology" -> known.components().contains(id);
            case "incident" -> id.equalsIgnoreCase(known.incidentId().toString());
            default -> false;
        };
    }

    private EvidenceCheck() {
    }
}
