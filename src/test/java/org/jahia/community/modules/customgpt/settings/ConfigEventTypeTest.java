package org.jahia.community.modules.customgpt.settings;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;
import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.osgi.FrameworkService;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for F16 (part 1): {@link Config#updated(Dictionary)}'s event-type selection.
 *
 * <p>{@code scheduleJobASAP=true} must fire {@link CustomGptConstants#EVENT_TYPE_CONFIG_UPDATED_REQUIRE_REINDEX}
 * (triggering immediate re-indexation in {@code Service.handleEvent()}); any other value fires the plain
 * {@link CustomGptConstants#EVENT_TYPE_CONFIG_UPDATED}. This is the mechanism that makes the "self-resetting"
 * write-back in {@code Service.resetScheduleJobASAP()} (part 2, see {@code ServiceResetScheduleJobASAPTest})
 * safe: writing {@code scheduleJobASAP=false} back re-fires {@code updated()} but with the non-reindex event
 * type, so it does not recursively re-trigger {@code reIndexUsingJob()}.
 */
public class ConfigEventTypeTest {

    private static final String NS = "org.jahia.community.modules.customgpt";
    private static final String KEY_API_BASE_URL = NS + ".apiBaseUrl";
    private static final String KEY_SCHEDULE_ASAP = NS + ".scheduleJobASAP";

    private static Dictionary<String, Object> validPropsWithScheduleAsap(boolean scheduleJobASAP) {
        final Dictionary<String, Object> d = new Hashtable<>();
        d.put(KEY_API_BASE_URL, "https://app.customgpt.ai/api/v1");
        d.put(KEY_SCHEDULE_ASAP, scheduleJobASAP);
        return d;
    }

    @SuppressWarnings("unchecked")
    @Test
    public void updated_scheduleJobASAPTrue_firesRequireReindexEventType() throws Exception {
        try (MockedStatic<FrameworkService> frameworkServiceStatic = mockStatic(FrameworkService.class)) {
            final Config config = new Config();

            config.updated(validPropsWithScheduleAsap(true));

            final ArgumentCaptor<Map<String, ?>> propsCaptor = ArgumentCaptor.forClass(Map.class);
            frameworkServiceStatic.verify(() -> FrameworkService.sendEvent(
                    org.mockito.ArgumentMatchers.eq(CustomGptConstants.EVENT_TOPIC), propsCaptor.capture(), anyBoolean()));
            assertThat(propsCaptor.getValue().get("type")).isEqualTo(CustomGptConstants.EVENT_TYPE_CONFIG_UPDATED_REQUIRE_REINDEX);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void updated_scheduleJobASAPFalse_firesPlainConfigUpdatedEventType() throws Exception {
        try (MockedStatic<FrameworkService> frameworkServiceStatic = mockStatic(FrameworkService.class)) {
            final Config config = new Config();

            config.updated(validPropsWithScheduleAsap(false));

            final ArgumentCaptor<Map<String, ?>> propsCaptor = ArgumentCaptor.forClass(Map.class);
            frameworkServiceStatic.verify(() -> FrameworkService.sendEvent(
                    org.mockito.ArgumentMatchers.eq(CustomGptConstants.EVENT_TOPIC), propsCaptor.capture(), anyBoolean()));
            assertThat(propsCaptor.getValue().get("type")).isEqualTo(CustomGptConstants.EVENT_TYPE_CONFIG_UPDATED);
        }
    }

    /**
     * Simulates the exact self-resetting sequence: a first {@code updated()} with
     * {@code scheduleJobASAP=true} (would trigger reindex), followed by the write-back
     * {@code updated()} with {@code scheduleJobASAP=false} that {@code Service.resetScheduleJobASAP()}
     * causes — proving the second call does NOT re-fire the reindex event type (non-recursive).
     */
    @Test
    public void selfResettingSequence_secondUpdateDoesNotReTriggerReindexEventType() throws Exception {
        try (MockedStatic<FrameworkService> frameworkServiceStatic = mockStatic(FrameworkService.class)) {
            final Config config = new Config();

            config.updated(validPropsWithScheduleAsap(true));
            config.updated(validPropsWithScheduleAsap(false));

            @SuppressWarnings("unchecked")
            final ArgumentCaptor<Map<String, ?>> propsCaptor = ArgumentCaptor.forClass(Map.class);
            frameworkServiceStatic.verify(() -> FrameworkService.sendEvent(anyString(), propsCaptor.capture(), anyBoolean()),
                    org.mockito.Mockito.times(2));

            final java.util.List<Map<String, ?>> allProps = propsCaptor.getAllValues();
            assertThat(allProps.get(0).get("type")).isEqualTo(CustomGptConstants.EVENT_TYPE_CONFIG_UPDATED_REQUIRE_REINDEX);
            assertThat(allProps.get(1).get("type")).isEqualTo(CustomGptConstants.EVENT_TYPE_CONFIG_UPDATED);
        }
    }
}
