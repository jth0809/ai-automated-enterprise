package com.aienterprise.backend.tracker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;

import com.aienterprise.backend.tracker.prediction.PredictionScorecard;
import com.aienterprise.backend.tracker.backtest.BacktestSkillDiagnostics;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class CredibilityControllerTest {

    private static final List<String> HONESTY = List.of(
            "ETA는 예보가 아니라 현 추세 지속을 가정한 시나리오 투영이며 구간은 모델 내부 민감도의 80%다. 모형족 오류·자료 선택 절차·목표 임계값 불확실성·외부 충격은 포함하지 않는다.",
            "수송 $ / kg은 실제 원가가 아니라 공개된 가격을 바탕으로 한 추정치다.",
            "관측 사건은 측정값이고 TRL/EGL 사상·가중치·DAG 집계는 구성 지수다.",
            "수송 경제성 임계값은 자연상수가 아니라 공개된 모델 가정이다.");

    @Autowired
    private CredibilityController controller;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ApplicationContext context;

    @Test
    void publishesAllSixExactReadOnlyRoutes() throws Exception {
        assertPath("methodology", "/methodology");
        assertPath("dag", "/dag");
        assertPath("projection", "/projections/current");
        assertPath("backtest", "/backtests/latest");
        assertPath("predictions", "/predictions");
        assertPath("scorecard", "/predictions/scorecard");
    }

    @Test
    void publishesDeterministicSeedsAsExactDecimalStrings() {
        assertEquals(String.class, recordType(
                CredibilityController.ProjectionResponse.class, "seed"));
        assertEquals(String.class, recordType(
                CredibilityController.BacktestResponse.class, "seed"));
        assertEquals(List.class, recordType(
                CredibilityController.BacktestResponse.class, "modelEvaluations"));
    }

    @Test
    void methodologyPublishesExactHonestyTextAndDarkRuntimeFlags() {
        CredibilityController.MethodologyResponse response = controller
                .methodology().getBody();

        assertEquals(HONESTY, response.honestyLabels());
        assertEquals("params-v2", response.modelParameters().params().version());
        assertEquals("hazard-v1", response.hazardParameters().version());
        assertEquals("graph-v1.0", response.graph().version());
        assertEquals(35, response.evidenceCoverage().activeNodeCount());
        assertTrue(response.formulas().containsKey("effectiveReadiness"));
        assertTrue(response.automaticFeatures().values().stream()
                .noneMatch(Boolean::booleanValue));
        assertFalse(context.containsBean("projectionService"));
        assertFalse(context.containsBean("backtestService"));
    }

    @Test
    void emptyCompletedRunsAreExplicitAndReadsDoNotStartCalculations() {
        int projectionsBefore = count("projection_run");
        int backtestsBefore = count("backtest_run");
        int cohortsBefore = count("prediction_cohort");

        assertEquals(CredibilityController.RunStatus.NOT_RUN,
                controller.projection().getBody().status());
        CredibilityController.BacktestResponse backtest = controller
                .backtest().getBody();
        assertEquals(CredibilityController.RunStatus.NOT_RUN, backtest.status());
        assertEquals(List.of(), backtest.modelEvaluations());
        assertEquals(BacktestSkillDiagnostics.SkillStatus.INSUFFICIENT_DATA,
                backtest.skillStatus());
        assertEquals(null, backtest.readinessMaeRatioVsPersistence());
        assertEquals(CredibilityController.PublicationStatus.EMPTY,
                controller.predictions().getBody().status());
        var scorecard = controller.scorecard().getBody();
        assertEquals(PredictionScorecard.Status.INSUFFICIENT_DATA,
                scorecard.groups().getFirst().status());
        assertEquals(0, scorecard.groups().getFirst().sampleCount());

        assertEquals(projectionsBefore, count("projection_run"));
        assertEquals(backtestsBefore, count("backtest_run"));
        assertEquals(cohortsBefore, count("prediction_cohort"));
    }

    @Test
    void dagPublishesValidatedEdgesAndTextualLimitReasons() {
        CredibilityController.DagResponse dag = controller.dag().getBody();

        assertEquals("graph-v1.0", dag.graphVersion());
        assertEquals(dag.edgeCount(), dag.edges().size());
        assertEquals(35, dag.nodes().size());
        assertTrue(dag.nodes().stream().allMatch(node ->
                node.limitingGroups() != null
                        && node.limitingDependencies() != null));
    }

    private static void assertPath(String methodName, String expected)
            throws Exception {
        Method method = CredibilityController.class.getMethod(methodName);
        assertEquals(List.of(expected),
                List.of(method.getAnnotation(GetMapping.class).value()));
    }

    private static Class<?> recordType(Class<?> type, String name) {
        return java.util.Arrays.stream(type.getRecordComponents())
                .filter(component -> component.getName().equals(name))
                .findFirst().orElseThrow().getType();
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table)
                .query(Integer.class).single();
    }
}
