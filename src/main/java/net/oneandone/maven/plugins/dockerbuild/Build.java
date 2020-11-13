/*
 * Copyright 1&1 Internet AG, https://github.com/1and1/
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
package net.oneandone.maven.plugins.dockerbuild;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageCmd;
import net.oneandone.maven.plugins.dockerbuild.model.Arguments;
import net.oneandone.maven.plugins.dockerbuild.model.BuildListener;
import net.oneandone.maven.plugins.dockerbuild.model.Context;
import net.oneandone.maven.plugins.dockerbuild.model.Placeholders;
import net.oneandone.sushi.fs.file.FileNode;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.plugins.annotations.Component;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;

/**
 * Builds a Docker image.
 */
@Mojo(name = "build", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.NONE, threadSafe = true)
public class Build extends Base {
    @Parameter(required = true)
    private final String dockerbuild;

    @Parameter()
    private final String version;

    /** Don't use Docker cache */
    @Parameter(defaultValue = "false")
    private final boolean noCache;

    @Parameter(property = "dockerbuild.library", defaultValue = "com.dockerbuild.library")
    private final String library;

    /** inspired by https://maven.fabric8.io/#image-configuration */
    @Parameter(property = "dockerbuild.image", defaultValue = "%g/%a:%V")
    private final String image;

    /** Explicit comment to add to image */
    @Parameter(property = "dockerbuild.comment", defaultValue = "")
    private final String comment;

    /** Dockerfile argument */
    @Parameter
    private Map<String, String> arguments;

    @Parameter(defaultValue = "${project.build.finalName}", readonly = true)
    private String buildFinalName;

    @Parameter(property = "project", required = true, readonly = true)
    private final MavenProject project;

    //--

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepos;

    public Build() throws IOException {
        this.library = null;
        this.dockerbuild = null;
        this.version = null;
        this.noCache = false;
        this.image = "";
        this.comment = "";
        this.arguments = new HashMap<>();
        this.project = null;
    }

    @Override
    public void doExecute(DockerClient docker) throws IOException, MojoExecutionException {
        Log log;
        String repositoryTag;
        Context context;
        long started;
        FileNode jar;
        Map<String, String> actuals;
        String id;
        BuildImageCmd build;
        FileNode buildLog;

        log = getLog();
        repositoryTag = new Placeholders(project).resolve(image);
        jar = resolveDockerbuild();
        log.info("cd " + context() + " && jar xf " + jar);
        context = Context.create(jar, dockerbuild, context());
        buildLog = buildLog();
        buildLog.getParent().mkdirsOpt();
        started = System.currentTimeMillis();
        imageFile().getParent().mkdirsOpt();
        imageFile().writeString(repositoryTag);
        Arguments a = context.arguments(log);
        a.addArtifacts(context, world.file(project.getBuild().getDirectory()), project.getBuild().getFinalName());
        a.addBuild(comment);
        a.addPom(project);
        a.addExplicit(arguments);
        actuals = a.result();
        try (InputStream tarSrc = context.tar().newInputStream()) {
            build = docker.buildImageCmd()
                    .withTarInputStream(tarSrc)
                    .withNoCache(noCache)
                    .withTags(Collections.singleton(repositoryTag));
            for (Map.Entry<String, String> entry : actuals.entrySet()) {
                build.withBuildArg(entry.getKey(), entry.getValue());
            }
            log.info(cli(repositoryTag, noCache, actuals, context, buildLog));
            try (PrintWriter logfile = new PrintWriter(buildLog.newWriter())) {
                id = build.exec(new BuildListener(log, logfile)).awaitImageId();
            }
        } catch (MojoExecutionException e) {
            log.error("build failed");
            for (String line : buildLog.readLines()) {
                log.error("  " + line);
            }
            throw e;
        }
        log.info("Done: tag=" + repositoryTag + " id=" + id + " seconds=" + (System.currentTimeMillis() - started) / 1000);
    }

    /** command-line equivalant of the rest call we're using */
    private static String cli(String repositoryTag, boolean noCache, Map<String, String> actuals, Context context, FileNode buildLog) {
        StringBuilder cli;

        cli = new StringBuilder("docker build -t \"" + repositoryTag + '"');
        if (noCache) {
            cli.append(" --no-cache");
        }
        for (Map.Entry<String, String> entry : actuals.entrySet()) {
            cli.append(" --build-arg ");
            cli.append(entry.getKey());
            cli.append('=');
            cli.append(entry.getValue());
        }
        cli.append(" " + context);
        cli.append(" >" + buildLog);
        return cli.toString();
    }

    //-- artifact resolution, see https://maven.apache.org/resolver/maven-resolver-demos/maven-resolver-demo-maven-plugin/xref/index.html

    private FileNode resolveDockerbuild() throws MojoExecutionException {
        String gav;
        Artifact artifact;
        ArtifactRequest request;
        ArtifactResult result;
        FileNode file;

        gav = library + ":" + dockerbuild + ":" + checkedVersion();
        getLog().info("resolve " + gav);
        try {
            artifact = new DefaultArtifact(gav);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException("invalid dockerbuild gav: " + gav + ": " + e.getMessage(), e);
        }
        request = new ArtifactRequest();
        request.setArtifact(artifact);
        request.setRepositories(remoteRepos);

        try {
            result = repoSystem.resolveArtifact(repoSession, request);
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException(dockerbuild + ": failed to resolve dockerbuild: " + e.getMessage(), e);
        }
        file = world.file(result.getArtifact().getFile());
        return file;
    }

    private String checkedVersion() throws MojoExecutionException {
        String gav;
        Artifact artifact;
        VersionRangeRequest request;
        VersionRangeResult result;

        gav = library + ":" + dockerbuild + ":[" + (version == null ? "0" : version) + ",)";
        try {
            artifact = new DefaultArtifact(gav);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException("invalid dockerbuild version gav: " + gav + ": " + e.getMessage(), e);
        }
        request = new VersionRangeRequest();
        request.setArtifact(artifact);
        request.setRepositories(remoteRepos);
        try {
            result = repoSystem.resolveVersionRange(repoSession, request);
        } catch (VersionRangeResolutionException e) {
            throw new MojoExecutionException("dockerbuild version check failed: " + gav + ": " + e.getMessage(), e);
        }
        getLog().debug("available versions: " + result.getVersions());
        if (version == null) {
            return result.getHighestVersion().toString();
        } else {
            if (result.getHighestVersion() == null) {
                throw new MojoExecutionException(gav + ": no versions available");
            }
            if (!version.equals(result.getHighestVersion().toString())) {
                throw new MojoExecutionException("newer version(s) available: " + result);
            }
            return version;
        }
    }
}
