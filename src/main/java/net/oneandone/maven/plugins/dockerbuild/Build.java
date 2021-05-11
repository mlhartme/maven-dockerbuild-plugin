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
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.shared.filtering.MavenReaderFilter;
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
 * Builds a Docker image for this Maven module.
 */
@Mojo(name = "build", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.NONE, threadSafe = true)
public class Build extends Base {
    /** The maven groupId that contains the available docker builds */
    @Parameter(property = "dockerbuild.library", defaultValue = "com.dockerbuild.library")
    private final String library;

    /** Name of the dockerbuild to use. */
    @Parameter(required = true, property = "dockerbuild")
    private final String dockerbuild;

    /**
     * Version of the Dockerbuild to use. This plugin always uses the latest available version of the Dockerbuild; this parameter specifies
     * how to deal with version updates. Don't specify a version to pick the latest version without interaction.
     * Specify a version if you want to know about version updates: as long as you've specified the latest version, the build works normally;
     * if a newer build becomes available, the build fails with an error message indicating the latest version. */
    @Parameter()
    private final String version;

    /** Don't use Docker build cache */
    @Parameter(property = "dockerbuild.noCache", defaultValue = "false")
    private final boolean noCache;

    /** Image tag created by this build. Provides placeholders inspired by https://maven.fabric8.io/#image-configuration */
    @Parameter(property = "dockerbuild.image", defaultValue = "%g/%a:%V")
    private final String image;

    /** Explicit argument values passed to the build. */
    @Parameter
    private Map<String, String> arguments;

    //--

    @Component
    private MavenSession session;

    @Component
    private MavenReaderFilter readerFilter;

    @Component
    private RepositorySystem repoSystem;

    /** Used internally */
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    /** Used internally */
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepos;

    public Build() throws IOException {
        this.library = null;
        this.dockerbuild = null;
        this.version = null;
        this.noCache = false;
        this.image = "";
        this.arguments = new HashMap<>();
    }

    @Override
    public void doExecute(DockerClient docker) throws IOException, MojoExecutionException {
        Log log;
        String repositoryTag;
        FileNode contextDir;
        Context context;
        long started;
        FileNode jar;
        Map<String, String> actuals;
        String id;
        BuildImageCmd build;
        FileNode buildLog;

        log = getLog();
        if (skip) {
            log.info("build skipped");
            return;
        }
        jar = resolveDockerbuild();
        repositoryTag = new Placeholders(world.file(project.getBasedir()), project).resolve(image);
        contextDir = context();
        log.info("rm -rf " + contextDir + "; mkdir " + contextDir);
        log.info("(cd " + contextDir + " && jar xf " + jar + ")");

        context = Context.create(jar, dockerbuild, contextDir);
        buildLog = buildLog();
        buildLog.getParent().mkdirsOpt();
        started = System.currentTimeMillis();
        imageFile().getParent().mkdirsOpt();
        imageFile().writeString(repositoryTag);
        project.getProperties().put("dockerbuild.image", repositoryTag);
        project.getProperties().put("dockerbuild.origin", origin());
        actuals = new Arguments(log, context, readerFilter, project, session).eval(arguments);
        try (InputStream tarSrc = context.tar().newInputStream()) {
            build = docker.buildImageCmd()
                    .withTarInputStream(tarSrc)
                    .withNoCache(noCache)
                    .withTags(Collections.singleton(repositoryTag));
            for (Map.Entry<String, String> entry : actuals.entrySet()) {
                build.withBuildArg(entry.getKey(), entry.getValue());
            }
            log.info(cli(build, contextDir) + " >" + buildLog);
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
        log.info("Done: " + repositoryTag);
        log.debug("id=" + id + " seconds=" + (System.currentTimeMillis() - started) / 1000);
    }

    private static String origin() {
        try {
            return System.getProperty("user.name") + '@' + InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException e) {
            return "unknown host: " + e.getMessage();
        }
    }

    /** command-line equivalent of the rest call we're using */
    private static String cli(BuildImageCmd cmd, FileNode context) {
        StringBuilder cli;

        cli = new StringBuilder("docker build -t \"" + cmd.getTags().iterator().next() + '"');
        if (cmd.hasNoCacheEnabled()) {
            cli.append(" --no-cache");
        }
        cli.append(" \\\n");
        for (Map.Entry<String, String> entry : cmd.getBuildArgs().entrySet()) {
            cli.append("           --build-arg ");
            cli.append(entry.getKey());
            cli.append('=');
            cli.append(entry.getValue());
            cli.append(" \\\n");
        }
        cli.append(" " + context);
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
        if (result.getHighestVersion() == null) {
            throw new MojoExecutionException(gav + ": no versions available");
        }
        if (version == null) {
            return result.getHighestVersion().toString();
        } else {
            if (!version.equals(result.getHighestVersion().toString())) {
                throw new MojoExecutionException("newer version(s) available: " + result);
            }
            return version;
        }
    }
}
