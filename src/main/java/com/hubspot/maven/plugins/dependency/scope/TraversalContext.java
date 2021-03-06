package com.hubspot.maven.plugins.dependency.scope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;

public class TraversalContext {
  private static final String WILDCARD = "*";

  private final DependencyNode node;
  private final List<Artifact> path;
  private final Set<String> testScopedArtifacts;
  private final Set<Exclusion> exclusions;
  private final Map<String, Set<Exclusion>> dependencyManagementExclusions;

  private TraversalContext(DependencyNode node,
                           List<Artifact> path,
                           Set<String> testScopedArtifacts,
                           Set<Exclusion> exclusions,
                           Map<String, Set<Exclusion>> dependencyManagementExclusions) {
    this.node = node;
    this.path = Collections.unmodifiableList(path);
    this.testScopedArtifacts = Collections.unmodifiableSet(testScopedArtifacts);
    this.exclusions = Collections.unmodifiableSet(exclusions);
    this.dependencyManagementExclusions = toUnmodifiableMap(dependencyManagementExclusions);
  }

  public static TraversalContext newContextFor(DependencyNode node) {
    List<Artifact> path = Collections.singletonList(node.getArtifact());

    Set<String> testScopedArtifacts = new HashSet<>();
    for (DependencyNode dependency : node.getChildren()) {
      if (Artifact.SCOPE_TEST.equals(dependency.getArtifact().getScope())) {
        testScopedArtifacts.add(dependency.getArtifact().getDependencyConflictId());
      }
    }

    return new TraversalContext(
        node,
        path,
        testScopedArtifacts,
        Collections.<Exclusion>emptySet(),
        Collections.<String, Set<Exclusion>>emptyMap()
    );
  }

  public TraversalContext stepInto(MavenProject project, DependencyNode node) {
    String artifactKey = node.getArtifact().getDependencyConflictId();

    List<Artifact> path = new ArrayList<>(this.path);
    path.add(node.getArtifact());

    Map<String, Set<Exclusion>> dependencyManagementExclusions = new HashMap<>(this.dependencyManagementExclusions);
    if (project.getDependencyManagement() != null) {
      for (org.apache.maven.model.Dependency dependency : project.getDependencyManagement().getDependencies()) {
        Set<Exclusion> dependencyExclusions = dependencyManagementExclusions.get(dependency.getManagementKey());

        if (dependencyExclusions == null) {
          dependencyExclusions = new HashSet<>();
        } else {
          dependencyExclusions = new HashSet<>(dependencyExclusions);
        }

        for (org.apache.maven.model.Exclusion exclusion : dependency.getExclusions()) {
          dependencyExclusions.add(new Exclusion(exclusion.getGroupId(), exclusion.getArtifactId(), WILDCARD, WILDCARD));
        }

        dependencyManagementExclusions.put(dependency.getManagementKey(), dependencyExclusions);
      }
    }

    Set<Exclusion> exclusions = new HashSet<>(this.exclusions);
    if (dependencyManagementExclusions.containsKey(artifactKey)) {
      exclusions.addAll(dependencyManagementExclusions.get(artifactKey));
    }

    for (org.apache.maven.model.Dependency dependency : project.getDependencies()) {
      if (artifactKey.equals(dependency.getManagementKey())) {
        for (org.apache.maven.model.Exclusion exclusion : dependency.getExclusions()) {
          exclusions.add(new Exclusion(exclusion.getGroupId(), exclusion.getArtifactId(), WILDCARD, WILDCARD));
        }
      }
    }

    return new TraversalContext(node, path, testScopedArtifacts, exclusions, dependencyManagementExclusions);
  }

  public TraversalContext stepInto(ArtifactDescriptorResult artifactDescriptor, DependencyNode node) {
    String artifactKey = node.getArtifact().getDependencyConflictId();

    List<Artifact> path = new ArrayList<>(this.path);
    path.add(node.getArtifact());

    Set<Exclusion> exclusions = new HashSet<>(this.exclusions);
    if (dependencyManagementExclusions.containsKey(artifactKey)) {
      exclusions.addAll(dependencyManagementExclusions.get(artifactKey));
    }

    for (Dependency dependency : artifactDescriptor.getDependencies()) {
      if (artifactKey.equals(key(dependency))) {
        exclusions.addAll(dependency.getExclusions());
      }
    }

    return new TraversalContext(node, path, testScopedArtifacts, exclusions, dependencyManagementExclusions);
  }

  public boolean isOverriddenToTestScope(Dependency dependency) {
    return !excluded(dependency) && testScopedArtifacts.contains(key(dependency));
  }

  public Artifact currentArtifact() {
    return node.getArtifact();
  }

  public List<Artifact> path() {
    return path;
  }

  private boolean excluded(Dependency dependency) {
    for (Exclusion exclusion : exclusions) {
      if (matches(dependency, exclusion)) {
        return true;
      }
    }

    return false;
  }

  private static Map<String, Set<Exclusion>> toUnmodifiableMap(Map<String, Set<Exclusion>> modifiableMap) {
    Map<String, Set<Exclusion>> copy = new HashMap<>();
    for (Entry<String, Set<Exclusion>> entry : modifiableMap.entrySet()) {
      copy.put(entry.getKey(), Collections.unmodifiableSet(entry.getValue()));
    }

    return Collections.unmodifiableMap(copy);
  }

  private static boolean matches(Dependency dependency, Exclusion exclusion) {
    org.eclipse.aether.artifact.Artifact artifact = dependency.getArtifact();

    return artifact.getGroupId().equals(exclusion.getGroupId()) &&
        artifact.getArtifactId().equals(exclusion.getArtifactId()) &&
        (WILDCARD.equals(exclusion.getClassifier()) || artifact.getClassifier().equals(exclusion.getClassifier())) &&
        (WILDCARD.equals(exclusion.getExtension()) || artifact.getExtension().equals(exclusion.getExtension()));
  }

  private static String key(Dependency dependency) {
    org.eclipse.aether.artifact.Artifact artifact = dependency.getArtifact();
    String key = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getExtension();
    if (!artifact.getClassifier().isEmpty()) {
      key += ":" + artifact.getClassifier();
    }

    return key;
  }
}
