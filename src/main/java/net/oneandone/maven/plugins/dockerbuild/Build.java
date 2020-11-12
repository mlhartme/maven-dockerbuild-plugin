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
import net.oneandone.sushi.fs.file.FileNode;
import org.apache.maven.model.Scm;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
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
    public void doExecute(DockerClient docker) throws MojoFailureException, IOException, MojoExecutionException {
        Log log;
        String repositoryTag;
        Context context;
        long started;
        Map<String, BuildArgument> formals;
        Map<String, String> actuals;
        String id;
        BuildImageCmd build;
        StringBuilder cli;
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
        formals = BuildArgument.scan(context.dockerfile());
        actuals = buildArgs(formals);
        try (InputStream tarSrc = context.tar().newInputStream()) {
            build = docker.buildImageCmd()
                    .withTarInputStream(tarSrc)
                    .withNoCache(noCache)
                    .withTags(Collections.singleton(repositoryTag));
            for (Map.Entry<String, String> entry : actuals.entrySet()) {
                build.withBuildArg(entry.getKey(), entry.getValue());
            }
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
            log.info(cli.toString());
            try (PrintWriter logfile = new PrintWriter(buildLog.newWriter())) {
                id = build.exec(new BuildResults(log, logfile)).awaitImageId();
            }
        } catch (MojoFailureException | MojoExecutionException e) {
            log.error("build failed");
            for (String line : buildLog.readLines()) {
                log.error("  " + line);
            }
            throw e;
        }
        log.info("Done: tag=" + repositoryTag + " id=" + id + " seconds=" + (System.currentTimeMillis() - started) / 1000);
    }

    //--

    private static String origin() {
        try {
            return System.getProperty("user.name") + '@' + InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException e) {
            return "unknown host: " + e.getMessage();
        }
    }

    /** compute build argument values and add artifactArguments to context. */
    private Map<String, String> buildArgs(Map<String, BuildArgument> formals) throws MojoFailureException, IOException {
        final String artifactPrefix = "artifact";
        final String pomPrefix = "pom";
        final String xPrefix = "build";
        Map<String, String> result;
        String property;
        FileNode src;
        FileNode dest;
        String type;

        result = new HashMap<>();
        for (BuildArgument arg : formals.values()) {
            if (arg.name.startsWith(artifactPrefix)) {
                type = arg.name.substring(artifactPrefix.length()).toLowerCase();
                src = world.file(project.getBuild().getDirectory()).join(buildFinalName + "." + type);
                src.checkFile();
                dest = context().join(src.getName());
                src.copyFile(dest);
                result.put(arg.name, dest.getName());
                getLog().info("cp " + src + " " + dest);
            } else if (arg.name.startsWith(pomPrefix)) {
                switch (arg.name) {
                    case "pomScm":
                        result.put(arg.name, getScm());
                        break;
                    default:
                        throw new MojoFailureException("unknown pom argument: " + arg.name);
                }
            } else if (arg.name.startsWith(xPrefix)) {
                switch (arg.name) {
                    case "buildOrigin":
                        result.put(arg.name, origin());
                        break;
                    case "buildComment":
                        result.put(arg.name, comment);
                        break;
                    default:
                        throw new MojoFailureException("unknown build argument: " + arg.name);
                }
            } else {
                result.put(arg.name, arg.dflt);
            }
        }
        for (Map.Entry<String, String> entry : arguments.entrySet()) {
            property = entry.getKey();
            if (!result.containsKey(property)) {
                throw new MojoFailureException("unknown argument: " + property + "\n" + available(formals.values()));
            }
            result.put(property, entry.getValue());
        }
        for (Map.Entry<String, String> entry : result.entrySet()) {
            if (entry.getValue() == null) {
                throw new MojoFailureException("mandatory argument is missing: " + entry.getKey());
            }
        }
        return result;
    }

    private String getScm() throws MojoFailureException {
        Scm scm;
        String result;

        scm = project.getScm();
        result = scm.getDeveloperConnection();
        if (result != null) {
            return result;
        }
        result = scm.getConnection();
        if (result != null) {
            return result;
        }
        throw new MojoFailureException("pomScm argument: scm is not defined in this project");
    }

    private static String available(Collection<BuildArgument> args) {
        StringBuilder result;

        result = new StringBuilder();
        result.append("(available build arguments:");
        for (BuildArgument arg : args) {
            result.append(' ');
            result.append(arg.name);
        }
        result.append(")\n");
        return result.toString();
    }
}
