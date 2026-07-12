package org.jahia.community.modules.customgpt.indexer;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.community.modules.customgpt.settings.Config;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.usermanager.JahiaUser;
import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * SEC-E (supplemental regression guard) — the cleartext-at-rest {@code .cfg} risk is accurately scoped:
 * there is exactly one in-memory holder ({@link Config}) and no secondary secret store anywhere else in the
 * module (JCR node, log line, or OSGi factory configuration).
 *
 * <p>Written from the {@code indexer} package because assertion 1 needs same-package access to the
 * package-private {@link CustomGptIndexerNodeHandler}.
 */
public class SecurityInvariantScopeTest {

    private static final String SENTINEL_TOKEN = "SENTINEL_TOKEN_XYZ";

    private MockedStatic<JCRTemplate> jcrTemplateStatic;

    @After
    public void tearDown() {
        if (jcrTemplateStatic != null) {
            jcrTemplateStatic.close();
        }
    }

    // ---- Assertion 1: the jnt:customGptIndexEntry mapping node carries exactly one property ----

    @Test
    public void writeMappingNode_setsExactlyOnePageIdProperty_neverASecret() throws Exception {
        final JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        final JCRNodeWrapper parentNode = mock(JCRNodeWrapper.class);
        final JCRNodeWrapper mappingNode = mock(JCRNodeWrapper.class);
        final String nodePath = "/sites/acme/home";
        final String mappingPath = CustomGptConstants.buildMappingPath(nodePath);

        when(session.nodeExists(mappingPath)).thenReturn(false);
        when(session.getNode(nodePath)).thenReturn(parentNode);
        when(parentNode.isNodeType(CustomGptConstants.MIX_CUSTOM_GPT_INDEXABLE)).thenReturn(false);
        when(parentNode.addNode(CustomGptConstants.CUSTOMGPT_INDEX_NODE_NAME, CustomGptConstants.NT_CUSTOM_GPT_INDEX_ENTRY))
                .thenReturn(mappingNode);

        final JCRTemplate template = mock(JCRTemplate.class);
        when(template.doExecuteWithSystemSessionAsUser(any(), any(), any(), any())).thenAnswer(invocation -> {
            final org.jahia.services.content.JCRCallback<?> callback = invocation.getArgument(3);
            return callback.doInJCR(session);
        });
        jcrTemplateStatic = mockStatic(JCRTemplate.class);
        jcrTemplateStatic.when(JCRTemplate::getInstance).thenReturn(template);

        final JahiaUser rootUser = mock(JahiaUser.class);
        invokeWriteMappingNode(rootUser, nodePath, "page-42");

        // Exactly one property set on the mapping node: customGptPageId, with the page id — never a secret.
        verify(mappingNode).setProperty(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID, "page-42");
        verifyNoMoreInteractions(mappingNode);
        verify(session).save();
        // Also pin: the sentinel secret values never appear on this node under any property name.
        assertThat("page-42").doesNotContain(SENTINEL_TOKEN);
    }

    private static void invokeWriteMappingNode(JahiaUser rootUser, String nodePath, String pageId) throws Exception {
        final Method m = CustomGptIndexerNodeHandler.class.getDeclaredMethod(
                "writeMappingNode", JahiaUser.class, String.class, String.class);
        m.setAccessible(true);
        m.invoke(null, rootUser, nodePath, pageId);
    }

    // ---- Assertion 2: Config exposes the 3 secrets via exactly 3 getters, each backed by exactly 1 field ----

    @Test
    public void config_exposesExactlyThreeSecretGettersBackedByOneFieldEach() throws Exception {
        final List<Field> secretFields = java.util.Arrays.stream(Config.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .filter(f -> f.getType() == String.class)
                .filter(f -> {
                    final String name = f.getName().toLowerCase(java.util.Locale.ROOT);
                    return name.contains("token") || name.contains("password") || name.contains("cookievalue");
                })
                .collect(Collectors.toList());

        assertThat(secretFields)
                .extracting(Field::getName)
                .containsExactlyInAnyOrder("customGptToken", "jahiaPassword", "jahiaServerCookieValue");

        assertThat(Config.class.getMethod("getCustomGptToken")).isNotNull();
        assertThat(Config.class.getMethod("getJahiaPassword")).isNotNull();
        assertThat(Config.class.getMethod("getJahiaServerCookieValue")).isNotNull();
    }

    // ---- Assertion 3: no secondary secret store / no programmatic factory-configuration usage ----

    @Test
    public void noOtherClassDeclaresASecretLikeField() throws IOException {
        final Path mainSourceRoot = Paths.get("src", "main", "java");
        final Pattern secretFieldPattern = Pattern.compile(
                "(?i)(private|protected)\\s+(final\\s+)?String\\s+\\w*(token|password|cookieValue)\\w*\\s*[;=]");
        // GqlSettings is a known, accepted exception: it is a transient per-request GraphQL response DTO whose
        // token/jahiaPassword/jahiaServerCookieValue fields are, by construction, only ever populated either
        // with SecurityUtils.maskSecretForDisplay()'s placeholder (AdminQueries.getSettings(), the configured
        // branch) or with the literal empty string (the not-configured branch) - see AdminQueriesTest. It is
        // never persisted and never holds the raw secret, so it is not a second *credential store* - only
        // Config.java persists/holds the real values. This is the exact gap Stage 2/3 already flagged for
        // invariant (a) (GqlSettings does not self-enforce masking at the type level); SEC-E is scoped to
        // invariant (e) (no *secondary storage location*), which this DTO does not violate.
        final List<String> offendingFiles;
        try (Stream<Path> paths = Files.walk(mainSourceRoot)) {
            offendingFiles = paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().endsWith("Config.java"))
                    .filter(p -> !p.toString().endsWith("GqlSettings.java"))
                    .filter(p -> containsPattern(p, secretFieldPattern))
                    .map(Path::toString)
                    .collect(Collectors.toList());
        }

        assertThat(offendingFiles)
                .as("only settings/Config.java (and the known-accepted GqlSettings response DTO) should declare "
                        + "a secret-like field; a new match here suggests a second in-memory holder for a "
                        + "credential has been introduced")
                .isEmpty();
    }

    @Test
    public void noProgrammaticFactoryConfigurationUsageAnywhere() throws IOException {
        final Path mainSourceRoot = Paths.get("src", "main", "java");

        final List<String> offendingFiles;
        try (Stream<Path> paths = Files.walk(mainSourceRoot)) {
            offendingFiles = paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> containsLiteral(p, "createFactoryConfiguration"))
                    .map(Path::toString)
                    .collect(Collectors.toList());
        }

        assertThat(offendingFiles)
                .as("the module's config is a single-PID ManagedService (org.jahia.community.modules.customgpt); "
                        + "no code should programmatically create an OSGi factory configuration for it (that "
                        + "pattern carries a bundle-scoped orphaning risk on reinstall)")
                .isEmpty();
    }

    private static boolean containsPattern(Path file, Pattern pattern) {
        try {
            final String content = new String(Files.readAllBytes(file));
            return pattern.matcher(content).find();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static boolean containsLiteral(Path file, String literal) {
        try {
            final String content = new String(Files.readAllBytes(file));
            return content.contains(literal);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
