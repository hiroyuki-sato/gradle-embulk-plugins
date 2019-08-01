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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

/**
 * Configuration options for the {@link org.embulk.gradle.embulk_plugins.EmbulkPluginsPlugin}.
 *
 * <pre>{@code apply plugin: "org.embulk.embulk-plugins"
 *
 * embulkPlugin {
 *     mainClass = "org.embulk.input.example.ExampleInputPlugin"
 *     category = "input"
 *     type = "example"
 *     flatRuntimeConfiguration = "embulkPluginFlatRuntime"  // Not recommended to configure it.
 *     jruby = "org.jruby:jruby-complete:9.2.7.0"  // Not recommended to configure it.
 * }}</pre>
 */
public class EmbulkPluginExtension {
    public EmbulkPluginExtension(final Project project) {
        final ObjectFactory objectFactory = project.getObjects();

        this.project = project;
        this.mainClass = objectFactory.property(String.class);
        this.category = objectFactory.property(String.class);
        this.type = objectFactory.property(String.class);
        this.flatRuntimeConfiguration = objectFactory.property(String.class);
        this.flatRuntimeConfiguration.set("embulkPluginFlatRuntime");
        this.jruby = objectFactory.property(Object.class);
        this.jruby.set("org.jruby:jruby-complete:9.2.7.0");
    }

    public Property<String> getMainClass() {
        return this.mainClass;
    }

    public Property<String> getCategory() {
        return this.category;
    }

    public Property<String> getType() {
        return this.type;
    }

    public Property<String> getFlatRuntimeConfiguration() {
        return this.flatRuntimeConfiguration;
    }

    /**
     * Property to configure a dependency notation for JRuby to run `gem build` and `gem push` commands.
     */
    public Property<Object> getJruby() {
        return this.jruby;
    }

    public void checkValidity() {
        final ArrayList<String> errors = new ArrayList<>();
        if ((!this.mainClass.isPresent()) || this.mainClass.get().isEmpty()) {
            errors.add("'mainClass' must be available in 'embulkPlugin'.");
        }
        if ((!this.category.isPresent()) || this.category.get().isEmpty()) {
            errors.add("'category' must be available in 'embulkPlugin'.");
        }
        if (!CATEGORIES.contains(this.category.get())) {
            errors.add("'category' must be one of: " + String.join(", ", CATEGORIES_ARRAY));
        }
        if ((!this.type.isPresent()) || this.type.get().isEmpty()) {
            errors.add("'type' must be available in 'embulkPlugin'.");
        }

        if (!errors.isEmpty()) {
            throw new GradleException("[gradle-embulk-plugins] " + String.join(" ", errors));
        }
    }

    private static final String[] CATEGORIES_ARRAY = {
        "input",
        "output",
        "parser",
        "formatter",
        "decoder",
        "encoder",
        "filter",
        "guess",
        "executor"
    };

    private static final Set<String> CATEGORIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(CATEGORIES_ARRAY)));

    private final Project project;
    private final Property<String> mainClass;
    private final Property<String> category;
    private final Property<String> type;
    private final Property<String> flatRuntimeConfiguration;
    private final Property<Object> jruby;
}
