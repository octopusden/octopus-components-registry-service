package org.octopusden.octopus.escrow.model

interface IDependencyBuildResolveResult {

    void addDependency(IDependency dependency)

    void addAllDependencies(Set<IDependency> dependencies)

    Set<IDependency> getDependencies()
}
