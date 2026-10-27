package org.jahia.community.modules.customgpt.service;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that decides whether a failed refresh may be papered over with the answer already held.
 *
 * <p>Context: one slow call on a browsing node resolved to "not configured", that replaced a perfectly
 * good cached answer, the render produced a page with no assistant, and Jahia cached that page. The fault
 * lasted seconds; its consequence lasted hours. Keeping the previous answer across a transient failure is
 * what breaks that chain.
 *
 * <p>The rule has to be exact in both directions, which is what these pin down. Too permissive and a
 * deliberate de-configuration never takes effect; too strict and the original defect returns.
 */
public class SgeKeepPreviousAnswerTest {

    private static final String ID = "68402";
    private static final String KEY = "031eb8c9280864685bb92899580e04bf";
    private static final long MINUTE_MS = 60L * 1000L;

    /** Builds a cache entry whose deployment was genuinely resolved {@code ageMinutes} ago. */
    private static Object cacheEntry(SgeDeployment deployment, long ageMinutes) throws Exception {
        final Class<?> type = Class.forName(
                "org.jahia.community.modules.customgpt.service.Service$CachedSgeDeployment");
        final Constructor<?> ctor = type.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        final long resolvedAt = System.currentTimeMillis() - (ageMinutes * MINUTE_MS);
        return ctor.newInstance(deployment, resolvedAt, resolvedAt);
    }

    private static boolean keeps(Object cached, SgeDeployment resolved) throws Exception {
        final Class<?> entry = Class.forName(
                "org.jahia.community.modules.customgpt.service.Service$CachedSgeDeployment");
        final Method m = Service.class.getDeclaredMethod("keepsPreviousAnswer", entry, SgeDeployment.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, cached, resolved);
    }

    private static SgeDeployment failed(String reason) {
        return SgeDeployment.notConfigured(reason);
    }

    /** The case the whole thing exists for: the API could not be reached, so nothing should change. */
    @Test
    public void keepsAGoodAnswerWhenTheApiCannotBeReached() throws Exception {
        final Object cached = cacheEntry(SgeDeployment.of(ID, KEY, true), 2);
        assertThat(keeps(cached, failed(SgeDeployment.REASON_NO_DEPLOYMENT_KEY))).isTrue();
    }

    /** "Out of quota" is a real answer too, and just as worth keeping across an unreachable API. */
    @Test
    public void keepsAQuotaSpentAnswerToo() throws Exception {
        final Object cached = cacheEntry(SgeDeployment.of(ID, KEY, false), 2);
        assertThat(keeps(cached, failed(SgeDeployment.REASON_NO_DEPLOYMENT_KEY))).isTrue();
    }

    /*
     * Deliberate states must take effect immediately. Someone removing the project id, or breaking the API
     * base URL, is not a blip to ride out -- papering over those would make the module ignore its own
     * configuration, which is a worse failure than the one being prevented.
     */
    @Test
    public void lastsOnlyForAnUnreachableApi() throws Exception {
        final Object cached = cacheEntry(SgeDeployment.of(ID, KEY, true), 2);

        assertThat(keeps(cached, failed(SgeDeployment.REASON_NO_PROJECT_ID))).isFalse();
        assertThat(keeps(cached, failed(SgeDeployment.REASON_BAD_API_BASE_URL))).isFalse();
        assertThat(keeps(cached, failed(SgeDeployment.REASON_NO_HTTP_CLIENT))).isFalse();
        assertThat(keeps(cached, failed(SgeDeployment.REASON_NOT_CONFIGURED))).isFalse();
    }

    /** A success obviously replaces whatever was held. */
    @Test
    public void neverKeepsThePreviousAnswerOverASuccess() throws Exception {
        final Object cached = cacheEntry(SgeDeployment.of(ID, KEY, true), 2);
        assertThat(keeps(cached, SgeDeployment.of(ID, KEY, true))).isFalse();
    }

    /**
     * The bound. Minutes of failure are a blip; an hour of them is an outage or a project that no longer
     * exists, and then an old key must stop being served.
     */
    @Test
    public void stopsKeepingAnAnswerOnceItIsTooOld() throws Exception {
        final SgeDeployment good = SgeDeployment.of(ID, KEY, true);
        assertThat(keeps(cacheEntry(good, 59), failed(SgeDeployment.REASON_NO_DEPLOYMENT_KEY))).isTrue();
        assertThat(keeps(cacheEntry(good, 61), failed(SgeDeployment.REASON_NO_DEPLOYMENT_KEY))).isFalse();
    }

    /** Nothing held yet -- the first resolution after a restart -- has nothing to keep. */
    @Test
    public void keepsNothingWhenThereIsNoPreviousAnswer() throws Exception {
        assertThat(keeps(null, failed(SgeDeployment.REASON_NO_DEPLOYMENT_KEY))).isFalse();
    }

    /** A previous *failure* is not an answer, so one failure must not perpetuate the next. */
    @Test
    public void keepsNothingWhenThePreviousEntryWasItselfAFailure() throws Exception {
        final Object cached = cacheEntry(failed(SgeDeployment.REASON_NO_DEPLOYMENT_KEY), 2);
        assertThat(keeps(cached, failed(SgeDeployment.REASON_NO_DEPLOYMENT_KEY))).isFalse();
    }
}
