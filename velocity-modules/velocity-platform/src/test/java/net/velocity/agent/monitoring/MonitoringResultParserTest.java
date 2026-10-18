package net.velocity.agent.monitoring;

import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitoringResultParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String VALID = """
            {
              "outcome": "success",
              "summary": "One new listing matched.",
              "freshness": "Checked moments ago.",
              "observations": [
                {
                  "key": "k1",
                  "source_id": "s1",
                  "item_id": "i1",
                  "evidence": ["https://example.com/listings/1"],
                  "facts": {"price": 10, "title": "Widget"},
                  "relevance": "Matches the price criteria."
                }
              ],
              "updates": [
                {"observation_keys": ["k1"], "change": "new_match", "relevance": "New match.", "text": "Widget at 10."}
              ],
              "coverage": [{"source_id": "s1", "complete": true, "detail": "All pages read."}],
              "checkpoints": {"s1": "cursor-42"},
              "questions": [],
              "limitations": []
            }
            """;

    private final MonitoringResultParser parser = new MonitoringResultParser();

    @Test
    void parsesAWellFormedResultIntoDetachedRecords() {
        MonitoringResult result = parser.parse(VALID, 50, 50, 10_000);
        assertThat(result.outcome()).isEqualTo(MonitoringResult.Outcome.success);
        assertThat(result.summary()).isEqualTo("One new listing matched.");
        assertThat(result.observations()).singleElement().satisfies(observation -> {
            assertThat(observation.key()).isEqualTo("k1");
            assertThat(observation.sourceId()).isEqualTo("s1");
            assertThat(observation.itemId()).isEqualTo("i1");
            assertThat(observation.facts()).containsOnlyKeys("price", "title");
            assertThat(observation.facts().get("price").intValue()).isEqualTo(10);
        });
        assertThat(result.updates()).singleElement().satisfies(update -> {
            assertThat(update.observationKeys()).containsExactly("k1");
            assertThat(update.change()).isEqualTo(MonitoringResult.Change.new_match);
        });
        assertThat(result.coverage()).singleElement()
                .satisfies(coverage -> assertThat(coverage.complete()).isTrue());
        assertThat(result.checkpoints()).isEqualTo(Map.of("s1", "cursor-42"));
        assertThat(result.questions()).isEmpty();
        assertThat(result.completionEvidence()).isEmpty();
    }

    @Test
    void limitsAndInputMustBePresent() {
        assertThatThrownBy(() -> parser.parse(VALID, 0, 1, 1)).hasMessage("Invalid monitoring result limits");
        assertThatThrownBy(() -> parser.parse(null, 1, 1, 1))
                .hasMessage("Monitoring result is missing or exceeds its size limit");
        assertThatThrownBy(() -> parser.parse("not json", 1, 1, 1))
                .hasMessage("Invalid structured monitoring result");
        assertThatThrownBy(() -> parse(root -> root.put("outcome", "maybe")))
                .hasMessage("Invalid structured monitoring result");
    }

    @Test
    void unknownFieldsAndUnboundedValuesAreRejected() {
        assertThatThrownBy(() -> parse(root -> root.put("extra", 1)))
                .hasMessage("Unknown monitoring result field: extra");
        assertThatThrownBy(() -> parse(root -> observation(root).put("score", 1)))
                .hasMessage("Unknown monitoring result field: score");
        assertThatThrownBy(() -> parse(root -> root.put("outcome", " ")))
                .hasMessage("Monitoring result string is empty or exceeds its size limit");
        assertThatThrownBy(() -> parse(root -> root.put("summary", "x".repeat(16_001))))
                .hasMessage("Monitoring result string is empty or exceeds its size limit");
        assertThatThrownBy(() -> parse(root -> root.remove("checkpoints")))
                .hasMessage("checkpoints must be an object with at most 100 entries");
        assertThatThrownBy(() -> parser.parse(VALID, 50, 50, 10))
                .hasMessage("Observation facts must be a nonempty bounded object");
        assertThatThrownBy(() -> parse(root -> {
            array(root, "observations")
                    .add(observation(root).deepCopy().put("key", "k2").put("item_id", "i2"));
        }, 1))
                .hasMessage("observations must be a bounded array");
    }

    @Test
    void observationsNeedUniqueKeysUniqueItemsEvidenceAndSafeFactKeys() {
        assertThatThrownBy(
                () -> parse(
                        root -> array(root, "observations")
                                .add(observation(root).deepCopy().put("item_id", "i2"))))
                .hasMessage("Observation keys must be unique");
        assertThatThrownBy(
                () -> parse(
                        root -> array(root, "observations")
                                .add(observation(root).deepCopy().put("key", "k2"))))
                .hasMessage("A run cannot contain duplicate source items");
        assertThatThrownBy(() -> parse(root -> observation(root).putArray("evidence")))
                .hasMessage("Every observation requires an evidence reference");
        assertThatThrownBy(() -> parse(root -> observation(root).putObject("facts").put("a.b", 1)))
                .hasMessage("Fact keys cannot contain dots or start with dollar signs");
        assertThatThrownBy(() -> parse(root -> observation(root).putObject("facts").put("$set", 1)))
                .hasMessage("Fact keys cannot contain dots or start with dollar signs");
        assertThatThrownBy(() -> parse(root -> observation(root).putObject("facts")))
                .hasMessage("Observation facts must be a nonempty bounded object");
    }

    @Test
    void updatesAndCompletionEvidenceMustReferenceKnownObservationsOnce() {
        assertThatThrownBy(() -> parse(root -> update(root).putArray("observation_keys").add("missing")))
                .hasMessage("Candidate update has an invalid or duplicate observation reference");
        assertThatThrownBy(() -> parse(root -> update(root).putArray("observation_keys").add("k1").add("k1")))
                .hasMessage("Candidate update has an invalid or duplicate observation reference");
        assertThatThrownBy(() -> parse(root -> update(root).putArray("observation_keys")))
                .hasMessage("Candidate updates must reference observations");
        assertThatThrownBy(() -> parse(root -> root.putArray("completion_evidence").add("missing")))
                .hasMessage("Completion evidence must reference unique observation keys");
        assertThat(parse(root -> root.putArray("completion_evidence").add("k1")).completionEvidence())
                .containsExactly("k1");
    }

    @Test
    void coverageMustAccountForEveryObservedSource() {
        assertThatThrownBy(() -> parse(root -> coverage(root).put("source_id", "s2")))
                .hasMessage("Every observation source requires a coverage entry");
        assertThatThrownBy(() -> parse(root -> array(root, "coverage").add(coverage(root).deepCopy())))
                .hasMessage("Coverage sources must be unique");
        assertThatThrownBy(() -> parse(root -> coverage(root).put("complete", "yes")))
                .hasMessage("Coverage complete must be a boolean");
    }

    @Test
    void outcomesConstrainQuestionsCoverageAndCheckpoints() {
        assertThatThrownBy(() -> parse(root -> root.put("outcome", "needs_input")))
                .hasMessage("needs_input requires an actionable question");
        assertThat(parse(root -> {
            root.put("outcome", "needs_input");
            root.putArray("questions").add("Which region?");
        }).outcome()).isEqualTo(MonitoringResult.Outcome.needs_input);
        assertThatThrownBy(() -> parse(root -> coverage(root).put("complete", false)))
                .hasMessage("A successful result cannot claim incomplete source coverage");
        assertThatThrownBy(() -> parse(root -> root.put("outcome", "failed")))
                .hasMessage("Failed checks cannot advance checkpoints");
        assertThatThrownBy(() -> parse(root -> {
            root.put("outcome", "partial");
            coverage(root).put("complete", false);
        }))
                .hasMessage("Checkpoint proposals require complete coverage for the source");
        assertThat(parse(root -> {
            root.put("outcome", "partial");
            coverage(root).put("complete", false);
            root.putObject("checkpoints");
        }).checkpoints()).isEmpty();
    }

    private MonitoringResult parse(Consumer<ObjectNode> mutation) {
        return parse(mutation, 50);
    }

    private MonitoringResult parse(Consumer<ObjectNode> mutation, int maxObservations) {
        try {
            ObjectNode root = (ObjectNode) JSON.readTree(VALID);
            mutation.accept(root);
            return parser.parse(JSON.writeValueAsString(root), maxObservations, 50, 10_000);
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ArrayNode array(ObjectNode root, String field) {
        return (ArrayNode) root.get(field);
    }

    private static ObjectNode observation(ObjectNode root) {
        return (ObjectNode) root.get("observations").get(0);
    }

    private static ObjectNode update(ObjectNode root) {
        return (ObjectNode) root.get("updates").get(0);
    }

    private static ObjectNode coverage(ObjectNode root) {
        return (ObjectNode) root.get("coverage").get(0);
    }
}
