package org.jahia.community.modules.customgpt;

import org.jahia.services.content.JCRNodeWrapper;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DeleteRequest} in isolation (U8): getters, {@code requestType()}, and the
 * path+language equality/hashCode contract inherited from {@link AbstractCustomGptRequest}.
 *
 * <p>{@link DeleteRequest} is dead code in production today — see {@link DeadCodeRegressionGuardTest}
 * for the companion "nothing constructs a DeleteRequest" guard — but the class itself is still worth
 * pinning directly so a future reactivation starts from a known-correct base.
 */
public class DeleteRequestTest {

    private static JCRNodeWrapper nodeAt(String path) {
        final JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.getPath()).thenReturn(path);
        return node;
    }

    @Test
    public void requestType_isDelete() {
        final DeleteRequest request = new DeleteRequest(nodeAt("/sites/acme/home"), "en");

        assertThat(request.requestType()).isEqualTo(CustomGptRequest.RequestType.DELETE);
    }

    @Test
    public void gettersReturnConstructorValues() {
        final JCRNodeWrapper node = nodeAt("/sites/acme/home");
        final DeleteRequest request = new DeleteRequest(node, "fr");

        assertThat(request.getNode()).isSameAs(node);
        assertThat(request.getLanguage()).isEqualTo("fr");
    }

    @Test
    public void equals_samePathAndLanguage_areEqual() {
        final DeleteRequest a = new DeleteRequest(nodeAt("/sites/acme/home"), "en");
        final DeleteRequest b = new DeleteRequest(nodeAt("/sites/acme/home"), "en");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    public void equals_differentLanguage_areNotEqual() {
        final DeleteRequest a = new DeleteRequest(nodeAt("/sites/acme/home"), "en");
        final DeleteRequest b = new DeleteRequest(nodeAt("/sites/acme/home"), "fr");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    public void equals_differentPath_areNotEqual() {
        final DeleteRequest a = new DeleteRequest(nodeAt("/sites/acme/home"), "en");
        final DeleteRequest b = new DeleteRequest(nodeAt("/sites/acme/other"), "en");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    public void equals_differentRequestType_notEqualEvenWithSamePathAndLanguage() {
        // AbstractCustomGptRequest.equals() checks getClass() == obj.getClass(): an IndexRequest with the
        // same path/language must NOT be equal to a DeleteRequest.
        final DeleteRequest delete = new DeleteRequest(nodeAt("/sites/acme/home"), "en");
        final IndexRequest index = new IndexRequest(nodeAt("/sites/acme/home"), "en");

        assertThat(delete).isNotEqualTo(index);
    }

    @Test
    public void toString_containsPathAndLanguage() {
        final DeleteRequest request = new DeleteRequest(nodeAt("/sites/acme/home"), "en");

        assertThat(request.toString()).contains("/sites/acme/home").contains("en");
    }
}
