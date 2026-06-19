package org.jahia.community.modules.customgpt.service;

import java.lang.reflect.Method;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@code acceptablePathToIndex} predicate in {@link Service}.
 *
 * <p>The method is {@code public} and depends only on string operations (no JCR, no OSGi).
 * We exercise it via reflection rather than constructing a full {@code Service} (which requires
 * a live OSGi container with all {@code @Reference} dependencies satisfied).
 *
 * <p>The predicate returns {@code true} when:
 * <ul>
 *   <li>the path starts with {@code /sites/} (matching the internal SITE_MATCHER regex)
 *       or {@code /trash-}, AND</li>
 *   <li>the path does not end with {@code customGptPageId}, AND</li>
 *   <li>the path does not end with {@code jcr:lastModified}.</li>
 * </ul>
 */
public class ServiceAcceptablePathTest {

    /**
     * Invokes {@code Service.acceptablePathToIndex} on a bare (null-field) Service instance.
     * The method uses only its parameter and static final fields — no injected dependencies.
     */
    private boolean acceptable(String path) throws Exception {
        // Construct without triggering @Activate
        final Service service = newBareService();
        final Method m = Service.class.getDeclaredMethod("acceptablePathToIndex", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(service, path);
    }

    private static Service newBareService() throws Exception {
        // Use unsafe/objenesis-style construction to bypass the constructor if needed.
        // Actually Service has a default no-arg constructor (compiler-generated); OSGi
        // lifecycle methods (@Activate) are not called here.
        return Service.class.getDeclaredConstructor().newInstance();
    }

    // ---- /sites/ paths ----

    @Test
    public void acceptablePathToIndex_sitesPath_returnsTrue() throws Exception {
        assertThat(acceptable("/sites/acme/home")).isTrue();
    }

    @Test
    public void acceptablePathToIndex_deepSitesPath_returnsTrue() throws Exception {
        assertThat(acceptable("/sites/acme/home/about/team")).isTrue();
    }

    @Test
    public void acceptablePathToIndex_sitesRootSuffixOnly_returnsFalse() throws Exception {
        // "/sites" without a trailing slash does NOT match the "/sites/.+" regex
        assertThat(acceptable("/sites")).isFalse();
    }

    @Test
    public void acceptablePathToIndex_sitesSuffix_pathEndingWithCustomGptPageId_returnsFalse() throws Exception {
        assertThat(acceptable("/sites/acme/home/customGptPageId")).isFalse();
    }

    @Test
    public void acceptablePathToIndex_sitesSuffix_pathEndingWithJcrLastModified_returnsFalse() throws Exception {
        assertThat(acceptable("/sites/acme/home/jcr:lastModified")).isFalse();
    }

    // ---- /trash- paths ----

    @Test
    public void acceptablePathToIndex_trashPath_returnsTrue() throws Exception {
        assertThat(acceptable("/trash-acme/home")).isTrue();
    }

    @Test
    public void acceptablePathToIndex_trashPath_endingWithCustomGptPageId_returnsFalse() throws Exception {
        assertThat(acceptable("/trash-acme/customGptPageId")).isFalse();
    }

    // ---- unrelated paths ----

    @Test
    public void acceptablePathToIndex_modulesPath_returnsFalse() throws Exception {
        assertThat(acceptable("/modules/customgpt-ai/node")).isFalse();
    }

    @Test
    public void acceptablePathToIndex_usersPath_returnsFalse() throws Exception {
        assertThat(acceptable("/users/root")).isFalse();
    }

    @Test
    public void acceptablePathToIndex_emptyPath_returnsFalse() throws Exception {
        assertThat(acceptable("")).isFalse();
    }
}
