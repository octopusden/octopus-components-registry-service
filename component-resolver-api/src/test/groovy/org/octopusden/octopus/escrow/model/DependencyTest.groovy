package org.octopusden.octopus.escrow.model

class DependencyTest extends GroovyTestCase {

    public static final String GROUP_ID = "org.octopusden.octopus.system"
    public static final String ARTIFACT_ID = "svartifact"
    public static final String VERSION = "1.0.38-175"

    void testCREG174() {
        def dependency = Dependency.createFromCoordinates("org.octopusden.octopus.server:lib:1.7.3051:zip")
        assertEquals("org.octopusden.octopus.server", dependency.group)
        assertEquals("lib", dependency.name)
        assertEquals("1.7.3051", dependency.version)
        assertEquals("zip", dependency.type)
        assertNull(dependency.classifier)

        dependency = Dependency.createFromCoordinates("org.octopusden.octopus.server:lib:1.7.3051:zip:dist")
        assertEquals("org.octopusden.octopus.server", dependency.group)
        assertEquals("lib", dependency.name)
        assertEquals("1.7.3051", dependency.version)
        assertEquals("zip", dependency.type)
        assertEquals("dist", dependency.classifier)
    }

    void testCreateFromCoordinates() {
        def dependency = Dependency.createFromCoordinates("$GROUP_ID:$ARTIFACT_ID:$VERSION")
        assert new Dependency(group: GROUP_ID, name: ARTIFACT_ID, version: VERSION) == dependency
        assert new Dependency(group: GROUP_ID, name: ARTIFACT_ID, version: VERSION) == dependency
    }

    void testHashCode() {
        def dep1 = Dependency.createFromCoordinates("org.octopusden.octopus.system:commoncomponent:10.0.46")
        def dep2 = Dependency.createFromCoordinates("$GROUP_ID:$ARTIFACT_ID:$VERSION")
        def dep2_2 = Dependency.createFromCoordinates("$GROUP_ID:$ARTIFACT_ID:$VERSION")
        assert dep2 == dep2_2
        assert dep1.hashCode() != dep2.hashCode()
        assert dep1 != dep2
    }

}
