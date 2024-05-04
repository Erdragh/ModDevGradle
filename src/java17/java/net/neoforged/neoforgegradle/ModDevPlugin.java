package net.neoforged.neoforgegradle;

import org.gradle.api.GradleException;
import org.gradle.api.JavaVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.util.GradleVersion;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.stream.Collectors;

public class ModDevPlugin implements Plugin<Project> {
    private static final GradleVersion MIN_VERSION = GradleVersion.version("8.7");

    @Override
    public void apply(Project project) {
        if (GradleVersion.current().compareTo(MIN_VERSION) < 0) {
            throw new GradleException("To use the NeoForge plugin, please use at least " + MIN_VERSION + ". You are currently using " + GradleVersion.current() + ".");
        }

        project.getPlugins().apply(JavaLibraryPlugin.class);
        var extension = project.getExtensions().create("neoForge", NeoForgeExtension.class);

        var repositories = project.getRepositories();
        repositories.addLast(repositories.maven(repo -> {
            repo.setUrl(URI.create("https://maven.neoforged.net/releases/"));
        }));
        repositories.addLast(repositories.maven(repo -> {
            repo.setUrl(URI.create("https://libraries.minecraft.net/"));
            repo.metadataSources(sources -> sources.artifact());
        }));
        repositories.addLast(repositories.maven(repo -> {
            repo.setUrl(project.getLayout().getBuildDirectory().map(dir -> dir.dir("repo").getAsFile().getAbsolutePath()));
            repo.metadataSources(sources -> sources.mavenPom());
        }));

        var configurations = project.getConfigurations();
        var dependencyFactory = project.getDependencyFactory();
        var neoForgeModDev = configurations.create("neoForgeModDev", files -> {
            files.setCanBeConsumed(false);
            files.setCanBeResolved(true);
            files.defaultDependencies(spec -> {
                spec.addLater(extension.getVersion().map(version -> dependencyFactory.create("net.neoforged:neoforge:" + version + ":userdev")));
            });
        });
        var neoFormInABoxConfig = configurations.create("neoFormInABoxConfig", files -> {
            files.setCanBeConsumed(false);
            files.setCanBeResolved(true);
            files.defaultDependencies(spec -> {
                spec.add(dependencyFactory.create("net.neoforged:NeoFormInABox:1.0-SNAPSHOT"));
            });
        });
        var layout = project.getLayout();

        var tasks = project.getTasks();

        var createManifest = tasks.register("createArtifactManifest", CreateArtifactManifestTask.class, task -> {
            task.getNeoForgeModDevArtifacts().set(neoForgeModDev.getIncoming().getArtifacts().getResolvedArtifacts().map(results -> {
                return results.stream().map(result -> {
                    var gav = guessMavenGav(result);
                    return new ArtifactManifestEntry(
                            gav,
                            result.getFile()
                    );
                }).collect(Collectors.toSet());
            }));
            task.getManifestFile().set(layout.getBuildDirectory().file("neoform_artifact_manifest.properties"));
        });

        var createArtifacts = tasks.register("createMinecraftArtifacts", CreateMinecraftArtifactsTask.class, task -> {
            task.getArtifactManifestFile().set(createManifest.get().getManifestFile());
            task.getNeoForgeArtifact().set(extension.getVersion().map(version -> "net.neoforged:neoforge:" + version));
            task.getNeoFormInABox().from(neoFormInABoxConfig);
            task.getCompiledArtifact().set(layout.getBuildDirectory().file("repo/minecraft/minecraft-joined/local/minecraft-joined-local.jar"));
            task.getSourcesArtifact().set(layout.getBuildDirectory().file("repo/minecraft/minecraft-joined/local/minecraft-joined-local-sources.jar"));
        });

        var s2 = layout.getBuildDirectory().file("repo/minecraft/minecraft-joined/local/minecraft-joined-local.jar").get().getAsFile().toPath();
        if (!Files.exists(s2)) {
            try {
                Files.createDirectories(s2.getParent());
                Files.createFile(s2);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        var s = layout.getBuildDirectory().file("repo/minecraft/minecraft-joined/local/minecraft-joined-local.pom").get().getAsFile().toPath();
        if (!Files.exists(s)) {
            try {
                Files.writeString(s, """
                        <project xmlns="http://maven.apache.org/POM/4.0.0"
                                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                            <modelVersion>4.0.0</modelVersion>

                            <groupId>minecraft</groupId>
                            <artifactId>minecraft-joined</artifactId>
                            <version>local</version>
                            <packaging>jar</packaging>
                        </project>
                        """);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        var minecraftBinaries = createArtifacts.map(task -> project.files(task.getCompiledArtifact()));
        project.getDependencies().add("compileOnly", minecraftBinaries.get());

        var localRuntime = configurations.create("neoForgeGeneratedArtifacts", config -> {
            config.setCanBeResolved(false);
            config.setCanBeConsumed(false);
        });
        project.getDependencies().add(localRuntime.getName(), "minecraft:minecraft-joined:local");
        project.getDependencies().add("implementation", extension.getVersion().map(version -> dependencyFactory.create("net.neoforged:neoforge:" + version + ":universal")));
        project.getDependencies().add("compileOnly", "minecraft:minecraft-joined:local");
        configurations.named("runtimeClasspath", files -> files.extendsFrom(localRuntime));

        // Setup java toolchains if the current JVM isn't already J21 (how the hell did you load this plugin...)
        if (!JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_21)) {
            var javaExtension = ExtensionUtils.getExtension(project, "java", JavaPluginExtension.class);
            var toolchainSpec = javaExtension.getToolchain();
            toolchainSpec.getLanguageVersion().convention(JavaLanguageVersion.of(21));
        }
    }

    private static String guessMavenGav(ResolvedArtifactResult result) {
        String artifactId;
        String ext = "";
        String classifier = null;
        if (result.getId() instanceof ModuleComponentArtifactIdentifier moduleId) {
            var artifact = moduleId.getComponentIdentifier().getModule();
            var version = moduleId.getComponentIdentifier().getVersion();
            var expectedBasename = artifact + "-" + version;
            var filename = result.getFile().getName();
            var startOfExt = filename.lastIndexOf('.');
            if (startOfExt != -1) {
                ext = filename.substring(startOfExt + 1);
                filename = filename.substring(0, startOfExt);
            }

            if (filename.startsWith(expectedBasename + "-")) {
                classifier = filename.substring((expectedBasename + "-").length());
            }
            artifactId = moduleId.getComponentIdentifier().getGroup() + ":" + artifact + ":" + version;
        } else {
            ext = "jar";
            artifactId = result.getId().getComponentIdentifier().toString();
        }
        String gav = artifactId;
        if (classifier != null) {
            gav += ":" + classifier;
        }
        if (!"jar".equals(ext)) {
            gav += "@" + ext;
        }
        return gav;
    }
}
