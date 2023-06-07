package org.octopusden.octopus.escrow;

import org.octopusden.octopus.escrow.model.VCSSettings;
import org.octopusden.octopus.escrow.model.VersionControlSystemRoot;

import java.util.Arrays;

import static org.octopusden.octopus.escrow.RepositoryType.CVS;
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL;

class TestHelper {

    static VCSSettings createTestVCSSettings() {
        VersionControlSystemRoot root1 = VersionControlSystemRoot.create("cvs1", CVS, "OctopusSource/Octopus/Intranet",
                "TEST_COMPONENT2_${TEST_TEST_COMPONENT2_VERSION}", "TEST_COMPONENT2_03_38_30");
        VersionControlSystemRoot root2 = VersionControlSystemRoot.create("mercurial1", MERCURIAL, "ssh://hg@mercurial/zenit",
                "TEST_COMPONENT2_${TEST_TEST_COMPONENT2_VERSION}", "default");
        VersionControlSystemRoot root3 = VersionControlSystemRoot.create("cvs1", CVS, "OctopusSource/Octopus/Intranet",
                null, "TEST_COMPONENT2_03_38_30");

        return VCSSettings.create(Arrays.asList(root1, root2, root3));
    }
}
