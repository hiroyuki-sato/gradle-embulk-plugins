/*
 * Copyright 2019 The Embulk project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.embulk.gradle.embulk_plugins;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.maven.Conf2ScopeMapping;
import org.gradle.api.artifacts.maven.Conf2ScopeMappingContainer;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.component.SoftwareComponent;
import org.gradle.api.logging.Logger;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.MavenPlugin;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;

/**
 * A plugin for building Embulk plugins.
 *
 * <p>This implementation draws on Gradle's java-gradle-plugin plugin.
 *
 * @see <a href="https://github.com/gradle/gradle/blob/v5.5.1/subprojects/plugin-development/src/main/java/org/gradle/plugin/devel/plugins/JavaGradlePluginPlugin.java">JavaGradlePluginPlugin</a>
 */
public class EmbulkPluginsPlugin implements Plugin<Project> {
    @Override
    public void apply(final Project project) {
        // The "java" plugin is required to be applied so that the "runtime" configuration will be available.
        project.getPluginManager().apply(JavaPlugin.class);
        project.getPluginManager().apply(MavenPlugin.class);

        createExtension(project);

        project.getTasks().create("gem", Gem.class);
        project.getTasks().create("gemPush", GemPush.class);

        final Configuration runtimeConfiguration = project.getConfigurations().getByName("runtime");

        // It must be a non-detached configuration to be mapped into Maven scopes by Conf2ScopeMapping.
        final Configuration alternativeRuntimeConfiguration = project.getConfigurations().maybeCreate("embulkPluginRuntime");

        // It must be configured before evaluation (not in afterEvaluate).
        replaceConf2ScopeMappings(project, runtimeConfiguration, alternativeRuntimeConfiguration);

        project.afterEvaluate(projectAfterEvaluate -> {
            initializeAfterEvaluate(projectAfterEvaluate, runtimeConfiguration, alternativeRuntimeConfiguration);

            // Configuration#getResolvedConfiguration here so that the dependency lock state is checked.
            alternativeRuntimeConfiguration.getResolvedConfiguration();
        });
    }

    /**
     * Creates an extension of "embulkPlugin { ... }".
     *
     * <p>This method does not return the instance of {@code EmbulkPluginExtension} created because
     * it does not contain meaningful information before evaluate.
     */
    private static void createExtension(final Project project) {
        project.getExtensions().create(
                "embulkPlugin",
                EmbulkPluginExtension.class,
                project);
    }

    private static void initializeAfterEvaluate(
            final Project project,
            final Configuration runtimeConfiguration,
            final Configuration alternativeRuntimeConfiguration) {
        final EmbulkPluginExtension extension = project.getExtensions().getByType(EmbulkPluginExtension.class);

        extension.checkValidity();

        configureAlternativeRuntimeBasics(alternativeRuntimeConfiguration);

        // Dependencies of "embulkPluginRuntime" will be set only when "mainJar" is not configured.
        // If "mainJar" is set (ex. to "shadowJar"), developers need to configure "embulkPluginRuntime" by themselves.
        if (!extension.getMainJar().isPresent()) {
            configureAlternativeRuntimeDependencies(project, runtimeConfiguration, alternativeRuntimeConfiguration);
        }

        configureComponentsJava(project, alternativeRuntimeConfiguration);

        configureJarTask(project, extension);

        warnIfRuntimeHasCompileOnlyDependencies(project, alternativeRuntimeConfiguration);

        configureGemTasks(project, extension, alternativeRuntimeConfiguration);
    }

    /**
     * Configures the basics of the alternative (flattened) runtime configuration.
     */
    private static void configureAlternativeRuntimeBasics(final Configuration alternativeRuntimeConfiguration) {
        // The "embulkPluginRuntime" configuration has dependency locking activated by default.
        // https://docs.gradle.org/current/userguide/dependency_locking.html
        alternativeRuntimeConfiguration.getResolutionStrategy().activateDependencyLocking();

        // The "embulkPluginRuntime" configuration do not need to be transitive, and must be non-transitive.
        //
        // It contains all transitive dependencies of "runtime" flattened. It does not need to be transitive, then.
        // Moreover, setting it transitive troubles the warning shown by |warnIfRuntimeHasCompileOnlyDependencies|.
        //
        // The Embulk plugin developer may explicitly exclude some transitive dependencies as below :
        //
        //   dependencies {
        //       compile("org.glassfish.jersey.core:jersey-client:2.25.1") {
        //           exclude group: "javax.inject", module: "javax.inject"
        //       }
        //   }
        //
        // If "embulkPluginRuntime" is still transitive, it would finally contain "javax.inject:javax.inject".
        // The behavior is unintended. So, "embulkPluginRuntime" must be non-transitive.
        alternativeRuntimeConfiguration.setTransitive(false);
    }

    /**
     * Configures the alternative (flattened) runtime configuration with flattened dependencies.
     */
    private static void configureAlternativeRuntimeDependencies(
            final Project project,
            final Configuration runtimeConfiguration,
            final Configuration alternativeRuntimeConfiguration) {
        alternativeRuntimeConfiguration.withDependencies(dependencies -> {
            final Map<String, ResolvedDependency> allDependencies = new HashMap<>();
            final Set<ResolvedDependency> firstLevelDependencies =
                    runtimeConfiguration.getResolvedConfiguration().getFirstLevelModuleDependencies();
            for (final ResolvedDependency dependency : firstLevelDependencies) {
                if (dependency.getConfiguration().equals("runtimeElements")) {
                    // The target project may contain a non-"group:module:version" dependency. A subproject, for example,
                    // "compile project(':subproject-a')" should not be resolved as "group:module:version", nor be flattened.
                    // See also: https://discuss.gradle.org/t/determining-external-vs-sub-project-dependencies/12321
                    //
                    // Such a dependency is under the configuration "runtimeElements".
                    // https://docs.gradle.org/current/userguide/java_library_plugin.html#sec:java_library_configurations_graph
                    for (final ResolvedArtifact artifact : dependency.getModuleArtifacts()) {
                        // TODO: Consider nested subproject dependencies. See #54.
                        final ComponentIdentifier componentIdentifier = artifact.getId().getComponentIdentifier();
                        if (componentIdentifier instanceof ProjectComponentIdentifier) {
                            final Project dependencyProject =
                                    project.project(((ProjectComponentIdentifier) componentIdentifier).getProjectPath());
                            dependencies.add(project.getDependencies().create(dependencyProject));
                        }
                    }
                } else {
                    recurseAllDependencies(dependency, allDependencies);
                }
            }

            for (final ResolvedDependency dependency : allDependencies.values()) {
                final HashMap<String, String> notation = new HashMap<>();
                notation.put("group", dependency.getModuleGroup());
                notation.put("name", dependency.getModuleName());
                notation.put("version", dependency.getModuleVersion());
                dependencies.add(project.getDependencies().create(notation));
            }
        });
    }

    /**
     * Replaces {@code "runtime"} to the alternative runtime configuration in Gradle's {@code conf2ScopeMappings}
     * so that the alternative runtime configuration is used to generate pom.xml, instead of the standard
     * {@code "runtime"} configuration.
     *
     * <p>The mappings correspond Gradle configurations (e.g. {@code "compile"}, {@code "compileOnly"}) to
     * Maven scopes (e.g. {@code "compile"}, {@code "provided"}).
     *
     * <p>See {@code MavenPlugin.java} and {@code DefaultPomDependenciesConverter.java} for how Gradle is
     * converting Gradle dependencies to Maven POM dependencies.
     *
     * <p>Note that {@code conf2ScopeMappings} must be configured before evaluation (not in {@code afterEvaluate}).
     * See <a href="https://github.com/gradle/gradle/issues/1373">https://github.com/gradle/gradle/issues/1373</a>.
     *
     * @see <a href="https://github.com/gradle/gradle/blob/v5.5.1/subprojects/maven/src/main/java/org/gradle/api/publication/maven/internal/pom/DefaultPomDependenciesConverter.java">DefaultPomDependenciesConverter</a>
     * @see <a href="https://github.com/gradle/gradle/blob/v5.5.1/subprojects/maven/src/main/java/org/gradle/api/plugins/MavenPlugin.java#L171-L184">MavenPlugin#configureJavaScopeMappings</a>
     */
    private static void replaceConf2ScopeMappings(
            final Project project,
            final Configuration runtimeConfiguration,
            final Configuration alternativeRuntimeConfiguration) {
        final Object conf2ScopeMappingsObject = project.property("conf2ScopeMappings");
        if (!(conf2ScopeMappingsObject instanceof Conf2ScopeMappingContainer)) {
            throw new GradleException("Unexpected with \"conf2ScopeMappings\" not configured properly.");
        }
        final Conf2ScopeMappingContainer conf2ScopeMappingContainer = (Conf2ScopeMappingContainer) conf2ScopeMappingsObject;
        final Map<Configuration, Conf2ScopeMapping> conf2ScopeMappings = conf2ScopeMappingContainer.getMappings();
        conf2ScopeMappings.remove(runtimeConfiguration);
        conf2ScopeMappingContainer.addMapping(MavenPlugin.RUNTIME_PRIORITY + 1,
                                              alternativeRuntimeConfiguration,
                                              Conf2ScopeMappingContainer.RUNTIME);
    }

    /**
     * Configures "components.java" (SoftwareComponent used for "from components.java" in MavenPublication)
     * to include "embulkPluginRuntime" as the "runtime" scope of Maven.
     * https://docs.gradle.org/5.5.1/dsl/org.gradle.api.publish.maven.MavenPublication.html#N1C095
     * https://github.com/gradle/gradle/blob/v5.5.1/subprojects/plugins/src/main/java/org/gradle/api/plugins/JavaPlugin.java#L347-L354
     *
     * This SoftwareComponent configuration is used to in maven-publish.
     * https://github.com/gradle/gradle/blob/v5.5.1/subprojects/maven/src/main/java/org/gradle/api/publish/maven/internal/publication/DefaultMavenPublication.java#L344-L352
     * https://github.com/gradle/gradle/blob/v5.5.1/subprojects/maven/src/main/java/org/gradle/api/publish/maven/internal/publication/DefaultMavenPublication.java#L266-L277
     * https://github.com/gradle/gradle/blob/v5.5.1/subprojects/maven/src/main/java/org/gradle/api/publish/maven/internal/publication/DefaultMavenPublication.java#L354-L374
     */
    private static void configureComponentsJava(
            final Project project,
            final Configuration alternativeRuntimeConfiguration) {
        final SoftwareComponent component = project.getComponents().getByName("java");
        if (component instanceof AdhocComponentWithVariants) {
            ((AdhocComponentWithVariants) component).addVariantsFromConfiguration(alternativeRuntimeConfiguration, details -> {
                details.mapToMavenScope("runtime");
            });
        } else {
            throw new GradleException("Failed to configure components.java because it is not AdhocComponentWithVariants.");
        }
    }

    /**
     * Configures the standard {@code "jar"} task with required MANIFEST.
     */
    private static void configureJarTask(final Project project, final EmbulkPluginExtension extension) {
        final String mainJarTaskName;
        if (extension.getMainJar().isPresent()) {
            mainJarTaskName = extension.getMainJar().get();
        } else {
            mainJarTaskName = "jar";
        }
        project.getTasks().named(mainJarTaskName, Jar.class, jarTask -> {
            jarTask.manifest(UpdateManifestAction.builder()
                             .add("Embulk-Plugin-Main-Class", extension.getMainClass().get())
                             .add("Embulk-Plugin-Category", extension.getCategory().get())
                             .add("Embulk-Plugin-Type", extension.getType().get())
                             .add("Embulk-Plugin-Spi-Version", "0")
                             .add("Implementation-Title", project.getName())
                             .add("Implementation-Version", project.getVersion().toString())
                             .build());
        });
    }

    private static void warnIfRuntimeHasCompileOnlyDependencies(
            final Project project,
            final Configuration alternativeRuntimeConfiguration) {
        final Configuration compileOnlyConfiguration = project.getConfigurations().getByName("compileOnly");

        final Map<String, ResolvedDependency> compileOnlyDependencies =
                collectAllDependencies(compileOnlyConfiguration);
        final Map<String, ResolvedDependency> alternativeRuntimeDependencies =
                collectAllDependencies(alternativeRuntimeConfiguration);

        final Set<String> intersects = new HashSet<>();
        intersects.addAll(alternativeRuntimeDependencies.keySet());
        intersects.retainAll(compileOnlyDependencies.keySet());
        if (!intersects.isEmpty()) {
            final Logger logger = project.getLogger();

            // Logging in the "error" loglevel to show the severity, but not to fail with GradleException.
            logger.error(
                    "============================================ WARNING ============================================\n"
                    + "Following \"runtime\" dependencies are included also in \"compileOnly\" dependencies.\n"
                    + "\n"
                    + intersects.stream().map(key -> {
                          final ResolvedDependency dependency = alternativeRuntimeDependencies.get(key);
                          return "  \"" + dependency.getModule().toString() + "\"\n";
                      }).collect(Collectors.joining(""))
                    + "\n"
                    + "  \"compileOnly\" dependencies are used to represent Embulk's core to be \"provided\" at runtime.\n"
                    + "  They should be excluded from \"compile\" or \"runtime\" dependencies like the example below.\n"
                    + "\n"
                    + "  dependencies {\n"
                    + "    compile(\"org.glassfish.jersey.core:jersey-client:2.25.1\") {\n"
                    + "      exclude group: \"javax.inject\", module: \"javax.inject\"\n"
                    + "    }\n"
                    + "  }\n"
                    + "=================================================================================================");
        }
    }

    private static void configureGemTasks(
            final Project project,
            final EmbulkPluginExtension extension,
            final Configuration alternativeRuntimeConfiguration) {
        final TaskProvider<Gem> gemTask = project.getTasks().named("gem", Gem.class, task -> {
            final String mainJarTaskName;
            if (extension.getMainJar().isPresent()) {
                mainJarTaskName = extension.getMainJar().get();
            } else {
                mainJarTaskName = "jar";
            }
            task.dependsOn(mainJarTaskName);

            task.setEmbulkPluginMainClass(extension.getMainClass().get());
            task.setEmbulkPluginCategory(extension.getCategory().get());
            task.setEmbulkPluginType(extension.getType().get());

            if ((!task.getArchiveBaseName().isPresent())) {
                // project.getName() never returns null.
                // https://docs.gradle.org/5.5.1/javadoc/org/gradle/api/Project.html#getName--
                task.getArchiveBaseName().set(project.getName());
            }
            if ((!task.getArchiveClassifier().isPresent()) || task.getArchiveClassifier().get().isEmpty()) {
                task.getArchiveClassifier().set("java");
            }
            // summary is kept empty -- mandatory.
            if ((!task.getArchiveVersion().isPresent()) && (!project.getVersion().toString().equals("unspecified"))) {
                // project.getVersion() never returns null.
                // https://docs.gradle.org/5.5.1/javadoc/org/gradle/api/Project.html#getVersion--
                task.getArchiveVersion().set(buildGemVersionFromMavenVersion(project.getVersion().toString()));
            }

            task.getDestinationDirectory().set(((File) project.property("buildDir")).toPath().resolve("gems").toFile());
            task.from(alternativeRuntimeConfiguration, copySpec -> {
                copySpec.into("classpath");
            });
            task.from(((Jar) project.getTasks().getByName(mainJarTaskName)).getArchiveFile(), copySpec -> {
                copySpec.into("classpath");
            });
        });

        project.getTasks().named("gemPush", GemPush.class, task -> {
            task.dependsOn("gem");
            if (!task.getGem().isPresent()) {
                task.getGem().set(gemTask.get().getArchiveFile());
            }
        });
    }

    private static String buildGemVersionFromMavenVersion(final String mavenVersion) {
        if (mavenVersion.contains("-")) {
            final List<String> versionTokens = Arrays.asList(mavenVersion.split("-"));
            if (versionTokens.size() != 2) {
                throw new GradleException("Failed to convert the version \"" + mavenVersion + "\" to Gem-style.");
            }
            return versionTokens.get(0) + '.' + versionTokens.get(1).toLowerCase();
        }
        else {
            return mavenVersion;
        }
    }

    private static Map<String, ResolvedDependency> collectAllDependencies(final Configuration configuration) {
        final HashMap<String, ResolvedDependency> allDependencies = new HashMap<>();

        final Set<ResolvedDependency> firstLevel = configuration.getResolvedConfiguration().getFirstLevelModuleDependencies();
        for (final ResolvedDependency dependency : firstLevel) {
            recurseAllDependencies(dependency, allDependencies);
        }

        return Collections.unmodifiableMap(allDependencies);
    }

    /**
     * Traverses the dependency tree recursively to list all the dependencies.
     */
    private static void recurseAllDependencies(
            final ResolvedDependency dependency,
            final Map<String, ResolvedDependency> allDependencies) {
        final String key = dependency.getModuleGroup() + ":" + dependency.getModuleName();
        if (allDependencies.containsKey(key)) {
            return;
        }
        allDependencies.put(key, dependency);
        if (dependency.getChildren().size() > 0) {
            for (final ResolvedDependency child : dependency.getChildren()) {
                recurseAllDependencies(child, allDependencies);
            }
        }
    }

    static final String DEFAULT_JRUBY = "org.jruby:jruby-complete:9.2.7.0";
}
