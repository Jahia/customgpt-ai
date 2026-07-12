package org.jahia.community.modules.customgpt;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard pinning U8 ("{@link DeleteRequest} is dead code") and part of D3 ("the documented
 * delete pathway is inert; real deletion flows through a separate, undocumented mechanism").
 *
 * <p>Today, nothing in {@code src/main/java} constructs a {@code new DeleteRequest(...)} — actual page
 * deletion flows entirely through {@code Indexer.customGptPageToRemove}
 * (see {@code CustomGptIndexerNodeHandler.handleNodeToReindex()}, lines 95-97, exercised end-to-end by the
 * Tier-3 D1/D2 specs). If a future change wires up the dead {@code instanceof DeleteRequest} branch in
 * {@code CustomGptIndexerNodeHandler} without also adding real test coverage for it, this test fails the
 * build — forcing a deliberate decision instead of a silent reactivation.
 *
 * <p>D2's Tier-3 MockWebServer test additionally proves, at runtime, that the actual DELETE HTTP call
 * originates from the {@code customGptPageToRemove} loop strictly before the {@code requests} loop (which
 * would dispatch a {@code DeleteRequest} if one ever existed) begins iterating — this test only pins the
 * static "no construction site exists at all" half of D3.
 */
public class DeadCodeRegressionGuardTest {

    @Test
    public void noProductionCodeConstructsADeleteRequest() throws IOException {
        final Path mainSourceRoot = Paths.get("src", "main", "java");
        assertThat(Files.isDirectory(mainSourceRoot))
                .as("expected src/main/java to exist relative to the module root (the Maven working directory)")
                .isTrue();

        final List<String> offendingFiles;
        try (Stream<Path> paths = Files.walk(mainSourceRoot)) {
            offendingFiles = paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(this::containsNewDeleteRequestConstruction)
                    .map(Path::toString)
                    .collect(Collectors.toList());
        }

        assertThat(offendingFiles)
                .as("no file under src/main/java should construct 'new DeleteRequest(' today; if this now "
                        + "fails, DeleteRequest has been (re)activated and needs its own dedicated behavioural "
                        + "test coverage, not just this dead-code pin")
                .isEmpty();
    }

    private boolean containsNewDeleteRequestConstruction(Path file) {
        try {
            final String content = new String(Files.readAllBytes(file));
            return content.contains("new DeleteRequest(");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    public void deleteRequestClassFileStillExists() {
        // Sanity check that this guard is actually pointed at the right file (fails loudly if the class is
        // ever renamed/moved without updating this test).
        final File file = new File("src/main/java/org/jahia/community/modules/customgpt/DeleteRequest.java");
        assertThat(file).exists();
    }
}
