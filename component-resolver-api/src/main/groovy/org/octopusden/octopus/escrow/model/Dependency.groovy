package org.octopusden.octopus.escrow.model

import groovy.transform.ToString
import groovy.transform.TupleConstructor

@TupleConstructor
@ToString
//@EqualsAndHashCode it doens't work
final class Dependency implements Serializable, IDependency {

    private static final long serialVersionUID = -661232368456890961L;

    static Dependency createFromCoordinates(String mavenArtifactCoordinates) {
        def segments = Objects.requireNonNull(mavenArtifactCoordinates).split(":")
        switch (segments.length) {
            case 3:
                return new Dependency(group: segments[0], name: segments[1], version: segments[2])
            case 4:
                return new Dependency(group: segments[0], name: segments[1], version: segments[2], type: segments[3])
            case 5:
                return new Dependency(group: segments[0], name: segments[1], version: segments[2], type: segments[3], classifier: segments[4])
            default:
                throw new IllegalArgumentException("Invalid maven artifact specification: '" + mavenArtifactCoordinates + "'. Valid formats are groupId:artifactId:version, groupId:artifactId:version:packaging and groupId:artifactId:version:packaging:classifier")
        }
    }

    private String group
    private String name
    private String version
    private String classifier
    private String type

    @Override
    String getGroup() {
        return group
    }

    @Override
    String getName() {
        return name
    }

    @Override
    String getVersion() {
        return version
    }

    @Override
    String getType() {
        return type
    }

    @Override
    String getClassifier() {
        return classifier
    }

    @Override
    String toString() {
        return "$group:$name:$version:$classifier:$type"
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        Dependency that = (Dependency) o

        if (classifier != that.classifier) return false
        if (group != that.group) return false
        if (name != that.name) return false
        if (type != that.type) return false
        if (version != that.version) return false

        return true
    }

    int hashCode() {
        int result
        result = (group != null ? group.hashCode() : 0)
        result = 31 * result + (name != null ? name.hashCode() : 0)
        result = 31 * result + (version != null ? version.hashCode() : 0)
        result = 31 * result + (classifier != null ? classifier.hashCode() : 0)
        result = 31 * result + (type != null ? type.hashCode() : 0)
        return result
    }
}
