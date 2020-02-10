Gradle plugin for Embulk plugins
=================================

Quick Guide
------------

```
plugins {
    id "java"
    id "maven"  // To release with upload (uploadArchives).
    id "maven-publish"  // To release with publishing.

    // Once this Gradle plugin is applied, its transitive dependencies are automatically updated to be flattened.
    // The update affects the default `jar` task, and default Maven uploading mechanisms as well.
    id "org.embulk.embulk-plugins" version "0.2.7"
}

group = "com.example"
version = "0.1.5-ALPHA"
description = "An Embulk plugin to load example data."

repositories {
    mavenCentral()
    jcenter()
}

dependencies {
    compileOnly "org.embulk:embulk-core:0.9.17"

    // Take care that other dependencies do not have transitive dependencies to `embulk-core` and its dependencies.
    // You'll need to exclude those transitive dependencies explicitly in that case.
    //
    // For example:
    // compile("org.embulk.base.restclient:embulk-base-restclient:0.7.0") {
    //     exclude group: "org.embulk", module: "embulk-core"
    // }

    testCompile "junit:junit:4.13"
}

embulkPlugin {
    mainClass = "org.embulk.input.example.ExampleInputPlugin"
    category = "input"
    type = "example"
}

// This Gradle plugin's POM dependency modification works for Upload tasks.
uploadArchives {
    repositories {
        mavenDeployer {
            repository(url: "file:${project.buildDir}/mavenLocal")
            snapshotRepository(url: "file:${project.buildDir}/mavenLocalSnapshot")
        }
    }
}

// This Gradle plugin's POM dependency modification works for "maven-publish" tasks.
publishing {
    publications {
        embulkPluginMaven(MavenPublication) {  // Publish it with "publishEmbulkPluginMavenPublicationToMavenRepository".
            from components.java  // Must be "components.java". The dependency modification works only for it.
        }
    }
    repositories {
        maven {
            url = "${project.buildDir}/mavenPublishLocal"
        }
    }
}

// Enable this when you want to publish your plugin as a gem.
// Note that `gem` is a type of archive tasks such as `jar` and `zip`, with some additional properties to fulfill `.gemspec`.
//
// gem {
//     from("LICENSE")  // Optional -- if you need other files in the gem.
//     authors = [ "Somebody Somewhere" ]
//     email = [ "somebody@example.com" ]
//     // "description" of the gem is copied from "description" of your Gradle project.
//     summary = "Example input plugin for Embulk"
//     homepage = "https://example.com"
//     licenses = [ "Apache-2.0" ]
//     metadata = [  // Optional -- if you need metadata in the gem.
//         "foo": "bar"
//     ]
// }

// Enable this when you want to publish your plugin as a gem.
// Note that the `host` property is mandatory.
//
// gemPush {
//     host = "https://rubygems.org"
// }
```

### Dependency locking

The dependency configuration `embulkPluginRuntime`, which is added by this Gradle plugin for flattened dependencies, has [dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html) activated by default.

In the beginning(after migration old-style) of your Embulk plugin project, it is recommended for you to run `./gradlew dependencies --write-locks`, and add generated `gradle/dependency-locks/embulkPluginRuntime.lockfile` in your version control system. Your Embulk plugin project will have more sensitive checks on its dependency libraries, then.

### How to migrate old-style `build.gradle` of your Embulk plugins

1. Upgrade your Gradle wrapper to `5.5.1`.
    * This plugin does not work with Gradle 6 yet.
2. Define `group`, `version`, and `description` in your Gradle project.
    * `group` should **NOT** be `"org.embulk"` unless your project is under: https://github.com/embulk. For example:
      ```
      group = "com.example"
      version = "0.1.5-SNAPSHOT"
      description = "An Embulk plugin to load example data."
      ```
3. Replace `compile` and `provided` in your dependencies to `compileOnly`.
    * Old:
      ```
      compile "org.embulk:embulk-core:0.9.17"
      provided "org.embulk:embulk-core:0.9.17"
      ```
    * New:
      ```
      compileOnly "org.embulk:embulk-core:0.9.17"
      ```
    * Take care that **other dependencies do not have transitive dependencies to `embulk-core` and its dependencies**. You'll need to exclude it explicitly those transitive dependencies explicitly in that case. For example:
      ```
      compile("org.embulk.base.restclient:embulk-base-restclient:0.7.0") {
          exclude group: "org.embulk", module: "embulk-core"
      }
      compile("org.glassfish.jersey.core:jersey-client:2.25.1") {
          exclude group: "javax.inject", module: "javax.inject"  // embulk-core depends on javax.inject.
      }
      ```
4. Add required `testCompile` if depending on `embulk-core:0.9.22+`.
    * If depending on `embulk-core:0.9.22`:
      ```
      // TODO: Remove it.
      // This `testCompile` is a tentative workaround. It will be covered in Embulk core's testing mechanism.
      testCompile "org.embulk:embulk-deps-buffer:0.9.22"
      ```
    * If depending on `embulk-core:0.9.23`:
      ```
      // TODO: Remove them.
      // These `testCompile` are a tentative workaround. It will be covered in Embulk core's testing mechanism.
      testCompile "org.embulk:embulk-deps-config:0.9.23"
      ```
5. **Remove** an unnecessary configuration.
    * `provided`
    ```
    configurations {
        provided
    }
    ```
6. **Remove** unnecessary tasks.
    * `classpath`
    ```
    task classpath(type: Copy, dependsOn: ["jar"]) {
        doFirst { file("classpath").deleteDir() }
        from (configurations.runtime - configurations.provided + files(jar.archivePath))
        into "classpath"
    }
    clean { delete "classpath" }
    ```
    * `gem`: a task with the same name is defined in this Gradle plugin
    ```
    task gem(type: JRubyExec, dependsOn: ["gemspec", "classpath"]) {
        jrubyArgs "-rrubygems/gem_runner", "-eGem::GemRunner.new.run(ARGV)", "build"
        script "${project.name}.gemspec"
        doLast { ant.move(file: "${project.name}-${project.version}.gem", todir: "pkg") }
    }
    ```
    * `gemPush`: a task with the same name is defined in this Gradle plugin
    ```
    task gemPush(type: JRubyExec, dependsOn: ["gem"]) {
        jrubyArgs "-rrubygems/gem_runner", "-eGem::GemRunner.new.run(ARGV)", "push"
        script "pkg/${project.name}-${project.version}.gem"
    }
    ```
    * `package`
    ```
    task "package"(dependsOn: ["gemspec", "classpath"]) {
        doLast {
            println "> Build succeeded."
            println "> You can run embulk with '-L ${file(".").absolutePath}' argument."
        }
    }
    ```
    * `gemspec`: the `gem` task defined in this Gradle plugin generates `.gemspec` under `build/`, and uses it to build a gem
    ```
    task gemspec {
        ext.gemspecFile = file("${project.name}.gemspec")
        inputs.file "build.gradle"
        outputs.file gemspecFile
        doLast { gemspecFile.write($/
    Gem::Specification.new do |spec|
      spec.name          = "${project.name}"
      spec.version       = "${project.version}"
      spec.authors       = ["Somebody Somewhere"]
      spec.summary       = %[Example input plugin for Embulk]
      spec.description   = %[An Embulk plugin to load example data.]
      spec.email         = ["somebody@example.com"]
      spec.licenses      = ["MIT"]
      spec.homepage      = "https://example.com"

      spec.files         = `git ls-files`.split("\n") + Dir["classpath/*.jar"]
      spec.test_files    = spec.files.grep(%r"^(test|spec)/")
      spec.require_paths = ["lib"]

      #spec.add_dependency 'YOUR_GEM_DEPENDENCY', ['~> YOUR_GEM_DEPENDENCY_VERSION']
      spec.add_development_dependency 'bundler'
      spec.add_development_dependency 'rake', ['>= 10.0']
    end
    /$)
        }
    }
    clean { delete "${project.name}.gemspec" }
    ```
7. Remove an unnecessary file.
    * `lib/embulk/<category>/<type>.rb`: the `gem` task defined in this Gradle plugin generates this `.rb` file under `build/` behind, and includes it in the gem. For example of `lib/embulk/input/example.rb`:
      ```
      Embulk::JavaPlugin.register_input(
        "example", "org.embulk.input.example.ExampleInputPlugin",
        File.expand_path('../../../../classpath', __FILE__))
      ```
8. Apply this Gradle plugin `"org.embulk.embulk-plugins"`.
    * Using the [plugins DSL](https://docs.gradle.org/current/userguide/plugins.html#sec:plugins_block):
      ```
      plugins {
          id "org.embulk.embulk-plugins" version "0.2.7"
      }
    * Using [legacy plugin application](https://docs.gradle.org/current/userguide/plugins.html#sec:old_plugin_application):
      ```
      buildscript {
          repositories {
          maven {
              url "https://plugins.gradle.org/m2/"
          }
      }
      dependencies {
          classpath "gradle.plugin.org.embulk:gradle-embulk-plugins:0.2.7"
      }

      apply plugin: "org.embulk.embulk-plugins"
      ```
9. Remove unnecessary JRuby/Gradle plugin.
    * Plugin application:
      ```
          id "com.github.jruby-gradle.base" version "0.1.5"
      ```
    * Class import:
      ```
      import com.github.jrubygradle.JRubyExec
      ```
10. Configure the task `embulkPlugin`.
    * `mainClass`, `category`, and `type` are mandatory. For example:
    ```
    embulkPlugin {
        mainClass = "org.embulk.input.dummy.DummyInputPlugin"
        category = "input"
        type = "dummy"
    }
11. Configure publishing the plugin JAR to the Maven repository where you want to upload.
    * Apply gradle plugins.
    ```
      plugins {
          id "maven"  // To release with upload (uploadArchives).
          id "maven-publish"  // To release with publishing.
      }
    ```
    * The standard `jar` task is already reconfigured to generate a JAR ready as an Embulk plugin.
    * Publishing example with `uploadArchives`:
    ```
    uploadArchives {
        repositories {
            mavenDeployer {
                repository(url: "file:${project.buildDir}/mavenLocal")
                snapshotRepository(url: "file:${project.buildDir}/mavenLocalSnapshot")
            }
        }
    }
    ```
    * Publishing example with `maven-publish`:
    ```
    publishing {
        publications {
            embulkPluginMaven(MavenPublication) {  // Publish it with "publishEmbulkPluginMavenPublicationToMavenRepository".
                from components.java  // Must be "components.java". The dependency modification works only for it.
            }
        }
        repositories {
            maven {
                url = "${project.buildDir}/mavenPublishLocal"
            }
        }
    }
    ```
12. Configure more to publish your plugin as a gem.
    * Configure the `gem` task. Note that `gem` is a type of archive tasks such as `jar` and `zip`, with some additional properties to fulfill `.gemspec`:
      ```
      gem {
          from("LICENSE")  // Optional -- if you need other files in the gem.
          authors = [ "Somebody Somewhere" ]
          email = [ "somebody@example.com" ]
          // "description" of the gem is copied from "description" of your Gradle project.
          summary = "Example input plugin for Embulk"
          homepage = "https://example.com"
          licenses = [ "Apache-2.0" ]
          metadata = [  // Optional -- if you need metadata in the gem.
              "foo": "bar"
          ]
      }
      ```
    * Configure the `gemPush` task. Note that the `host` property is mandatory:
      ```
      gemPush {
          host = "https://rubygems.org"
      }
      ```
    * Note that `rubygems` 2.7.9 embedded in JRuby 9.2.7.0 (the latest as of July 2019) does not support multi-factor authentication (OTP) yet. You'll need to set your authentication level to "UI only" when you push your gem into https://rubygems.org.
        * https://guides.rubygems.org/setting-up-multifactor-authentication/

What this Gradle plugin does?
------------------------------

This Gradle plugin does the following things for `jar` in addition to a normal Gradle build:

* Add some Embulk-specific attributes in generated JAR's manifest.
* Bring its transitive dependencies up flattened to the first level as `runtime`.
    * It is required in Embulk plugins because Embulk intentionally does not load transitive dependencies.
* Check that dependencies of `compileOnly` are not included in `runtime`.

And, it additionally provides some features for traditional `gem`-based Embulk plugins.
