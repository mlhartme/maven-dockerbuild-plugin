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
import java.util.Map;

/**
 * Builds a Docker image.
 */
@Mojo(name = "build", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.NONE, threadSafe = true)
public class Build extends Base {
    @Parameter(required = true)
    private final String dockerbuild;

    /** Don't use Docker cache */
    @Parameter(defaultValue = "false")
    private final boolean noCache;

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

    public Build() throws IOException {
        this.dockerbuild = null;
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
        Map<String, String> actuals;
        String id;
        BuildImageCmd build;
        FileNode buildLog;

        log = getLog();
        repositoryTag = new Placeholders(project).resolve(image);
        context = Context.create(context(), dockerbuild);
        log.info("extracted dockerbuild " + dockerbuild + " to " + context);
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
}
