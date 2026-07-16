package com.aienterprise.backend.tracker.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class ModelParameterRepositoryTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ModelParameterRepository repository;

    @Test
    void loadsAndValidatesSoleActiveParamsV2() {
        ModelParameters model = repository.loadActive();

        assertEquals("params-v2", model.params().version());
        assertEquals(4.0, model.params().kShrink());
        assertEquals(6, model.params().windowM());
        assertEquals(9, model.uncertainty().size());
        assertEquals(1.0, model.uncertainty().get("delta_scale").central());
    }

    @Test
    void rejectsZeroOrMultipleActiveVersions() {
        jdbc.sql("UPDATE parameter_set SET active = 'N'").update();
        assertThrows(IllegalStateException.class, repository::loadActive);

        jdbc.sql("""
                UPDATE parameter_set SET active = 'Y'
                 WHERE version_label IN ('params-v1','params-v2')
                """).update();
        assertThrows(IllegalStateException.class, repository::loadActive);
    }

    @Test
    void rejectsMalformedPersistedJsonBeforeCalculation() {
        jdbc.sql("""
                UPDATE parameter_set SET trl_map = '{'
                 WHERE version_label = 'params-v2'
                """).update();

        assertThrows(IllegalStateException.class, repository::loadActive);
    }

    @Test
    void rejectsPersistedUncertaintyNameDrift() {
        jdbc.sql("""
                UPDATE parameter_uncertainty
                   SET parameter_name = 'hidden_parameter'
                 WHERE parameter_name = 'delta_scale'
                   AND parameter_set_id = (
                     SELECT id FROM parameter_set WHERE version_label = 'params-v2')
                """).update();

        assertThrows(IllegalArgumentException.class, repository::loadActive);
    }
}
