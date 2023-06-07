package org.octopusden.octopus.escrow.model

class DependencyBuildResolveResult implements Serializable, IDependencyBuildResolveResult {

    private static final long serialVersionUID = -661232368456890121L;

    private Set<IDependency> dependencies = [] as HashSet

    @Override
    void addDependency(IDependency dependency) {
        this.dependencies.add(dependency)
    }

    @Override
    void addAllDependencies(Set<IDependency> dependencies) {
        this.dependencies.addAll(dependencies)
    }

    @Override
    Set<IDependency> getDependencies() {
        return dependencies
    }
}
