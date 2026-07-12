package org.jahia.community.modules.customgpt.service;

import java.lang.reflect.Method;
import java.util.Dictionary;
import java.util.Hashtable;
import org.jahia.osgi.BundleUtils;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for F16 (part 2): {@code Service.resetScheduleJobASAP()} — the private method that writes
 * {@code scheduleJobASAP=false} back through OSGi {@link ConfigurationAdmin} after a require-reindex event
 * has been handled, so the next config reload does not re-trigger {@code reIndexUsingJob()} a second time.
 *
 * <p>Invoked via reflection on a bare (non-activated) {@link Service} instance — the same technique already
 * established by {@code ServiceAcceptablePathTest} — since {@code resetScheduleJobASAP()} only touches the
 * static {@code BundleUtils.getOsgiService(ConfigurationAdmin.class, null)} lookup, not any injected
 * {@code @Reference} field.
 */
public class ServiceResetScheduleJobASAPTest {

    private static final String PID = "org.jahia.community.modules.customgpt";
    private static final String KEY_SCHEDULE_ASAP = PID + ".scheduleJobASAP";

    private static void invokeResetScheduleJobASAP(Service service) throws Exception {
        final Method m = Service.class.getDeclaredMethod("resetScheduleJobASAP");
        m.setAccessible(true);
        m.invoke(service);
    }

    @Test
    public void resetScheduleJobASAP_writesFalseBackThroughConfigurationAdmin() throws Exception {
        final Service service = Service.class.getDeclaredConstructor().newInstance();

        final ConfigurationAdmin configAdmin = mock(ConfigurationAdmin.class);
        final Configuration configuration = mock(Configuration.class);
        final Dictionary<String, Object> existingProps = new Hashtable<>();
        existingProps.put(KEY_SCHEDULE_ASAP, Boolean.TRUE);
        when(configuration.getProperties()).thenReturn(existingProps);
        when(configAdmin.getConfiguration(PID, null)).thenReturn(configuration);

        try (MockedStatic<BundleUtils> bundleUtilsStatic = mockStatic(BundleUtils.class)) {
            bundleUtilsStatic.when(() -> BundleUtils.getOsgiService(ConfigurationAdmin.class, null)).thenReturn(configAdmin);

            invokeResetScheduleJobASAP(service);
        }

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Dictionary<String, Object>> propsCaptor = ArgumentCaptor.forClass(Dictionary.class);
        verify(configuration).update(propsCaptor.capture());
        assertThat(propsCaptor.getValue().get(KEY_SCHEDULE_ASAP)).isEqualTo(Boolean.FALSE);
    }

    @Test
    public void resetScheduleJobASAP_nullConfigAdmin_isNoOp() throws Exception {
        final Service service = Service.class.getDeclaredConstructor().newInstance();

        try (MockedStatic<BundleUtils> bundleUtilsStatic = mockStatic(BundleUtils.class)) {
            bundleUtilsStatic.when(() -> BundleUtils.getOsgiService(ConfigurationAdmin.class, null)).thenReturn(null);

            // Must not throw when ConfigurationAdmin is unavailable.
            invokeResetScheduleJobASAP(service);
        }
    }

    @Test
    public void resetScheduleJobASAP_nullExistingProperties_createsNewDictionaryWithFalse() throws Exception {
        final Service service = Service.class.getDeclaredConstructor().newInstance();

        final ConfigurationAdmin configAdmin = mock(ConfigurationAdmin.class);
        final Configuration configuration = mock(Configuration.class);
        when(configuration.getProperties()).thenReturn(null);
        when(configAdmin.getConfiguration(PID, null)).thenReturn(configuration);

        try (MockedStatic<BundleUtils> bundleUtilsStatic = mockStatic(BundleUtils.class)) {
            bundleUtilsStatic.when(() -> BundleUtils.getOsgiService(ConfigurationAdmin.class, null)).thenReturn(configAdmin);

            invokeResetScheduleJobASAP(service);
        }

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Dictionary<String, Object>> propsCaptor = ArgumentCaptor.forClass(Dictionary.class);
        verify(configuration).update(propsCaptor.capture());
        assertThat(propsCaptor.getValue().get(KEY_SCHEDULE_ASAP)).isEqualTo(Boolean.FALSE);
    }
}
