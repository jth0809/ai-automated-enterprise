package com.aienterprise.backend.tracker.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.graph.EffectiveReadinessEngine;

class ProjectionFingerprintTest {

    @Test
    void canonicalHashAndSeedIgnoreCollectionOrder() {
        ProjectionInput original = ProjectionTestFixtures.input();
        var reversedNodes = new ArrayList<>(original.nodes());
        Collections.reverse(reversedNodes);
        var reversedGraph = ProjectionTestFixtures.graph(true);
        var readiness = new EffectiveReadinessEngine().calculate(
                reversedNodes, reversedGraph, original.model().params(), original.asOf());
        ProjectionInput reordered = new ProjectionInput(
                original.asOf(), original.datasetSha256(), original.nodeSetVersion(),
                reversedNodes, reversedGraph, original.model(), readiness,
                ProjectionTestFixtures.trends(readiness, java.util.Map.of(
                        1, .10, 2, .10, 3, .10, 4, .10, 5, .10, 6, .10)),
                ProjectionTestFixtures.momentum(true), original.sampleCount(),
                original.targetReadiness());

        ProjectionFingerprint.Value first = ProjectionFingerprint.of(original);
        ProjectionFingerprint.Value second = ProjectionFingerprint.of(reordered);

        assertEquals(first, second);
        assertTrue(first.sha256().matches("[0-9a-f]{64}"));
        assertTrue(first.seed() >= 0);
    }

    @Test
    void anySemanticInputChangeChangesHashAndSeed() {
        ProjectionInput original = ProjectionTestFixtures.input();
        var changedNodes = new ArrayList<>(original.nodes());
        NodeRow node = changedNodes.get(0);
        changedNodes.set(0, new NodeRow(
                node.id(), node.code(), node.pillar(), node.nameKo(),
                node.scaleType(), node.currentLevel() + 1, node.verificationLevel(),
                node.nodeStatus(), node.dormantSince(), node.programEndDate(),
                node.weight(), node.integrationNode(), node.description(),
                node.nodeSetVersion()));
        var readiness = new EffectiveReadinessEngine().calculate(
                changedNodes, original.graph(), original.model().params(), original.asOf());
        ProjectionInput changed = new ProjectionInput(
                original.asOf(), original.datasetSha256(), original.nodeSetVersion(),
                changedNodes, original.graph(), original.model(), readiness,
                ProjectionTestFixtures.trends(readiness, java.util.Map.of(
                        1, .10, 2, .10, 3, .10, 4, .10, 5, .10, 6, .10)),
                original.momentum(), original.sampleCount(), original.targetReadiness());

        ProjectionFingerprint.Value first = ProjectionFingerprint.of(original);
        ProjectionFingerprint.Value second = ProjectionFingerprint.of(changed);

        assertNotEquals(first.sha256(), second.sha256());
        assertNotEquals(first.seed(), second.seed());
    }
}
