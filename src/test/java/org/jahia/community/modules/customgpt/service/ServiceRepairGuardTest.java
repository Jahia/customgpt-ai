package org.jahia.community.modules.customgpt.service;

import org.junit.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Guard rails on the URL repair entry point: a site key that could escape /sites/, and an uninitialised module. */
public class ServiceRepairGuardTest {

    private static Service newService() throws Exception {
        return Service.class.getDeclaredConstructor().newInstance();
    }

    @Test
    public void aSiteKeyThatWouldEscapeTheSitesPathIsRejected() throws Exception {
        final Service service = newService();
        // The key is interpolated into a JCR query constraint; anything with a path separator is refused.
        assertThatThrownBy(() -> service.repairMissingPageUrls("acme/../../etc", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.repairMissingPageUrls("", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.repairMissingPageUrls("a/b", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void anUninitialisedModuleReportsThatRatherThanFailingLater() throws Exception {
        assertThatThrownBy(() -> newService().repairMissingPageUrls("academy", true))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not initialised");
    }
}
