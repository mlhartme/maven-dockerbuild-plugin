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

import net.oneandone.stool.docker.BuildError;
import net.oneandone.stool.docker.Daemon;
import net.oneandone.stool.docker.ImageInfo;
import net.oneandone.sushi.fs.Node;
import net.oneandone.sushi.fs.World;
import net.oneandone.sushi.fs.file.FileNode;
import net.oneandone.sushi.fs.http.StatusException;
import net.oneandone.sushi.launcher.Launcher;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.IOException;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds a Docker image.
 */
@Mojo(name = "build", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.NONE, threadSafe = true)
public class Build extends AbstractMojo {
    private final World world;

    @Parameter(required = true)
    private final String dockerbuild;

    /** Don't use Docker cache */
    @Parameter(defaultValue = "false")
    private final boolean noCache;

    @Parameter(property = "docker.image", defaultValue = "${project.groupId}-${project.artifactId}-${project.version}") // TODO
    private final String image;

    /** Explicit comment to add to image */
    @Parameter(defaultValue = "")
    private final String comment;

    /** Dockerfile argument */
    @Parameter
    private Map<String, String> arguments;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private String buildDirectory;

    @Parameter(defaultValue = "${project.build.finalName}", readonly = true)
    private String buildFinalName;

    public Build() throws IOException {
        this(World.create());
    }

    public Build(World world) {
        this.world = world;
        this.dockerbuild = null;
        this.noCache = false;
        this.image = "";
        this.comment = "";
        this.arguments = new HashMap<>();
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            build();
        } catch (IOException e) {
            throw new MojoExecutionException("io error: " + e.getMessage(), e);
        }
    }

    public void build() throws IOException, MojoFailureException, MojoExecutionException {
        try (Daemon daemon = Daemon.create()) {
            build(daemon, world.file(buildDirectory).join("dockerbuild"));
        } catch (StatusException e) {
            throw new MojoFailureException("docker build failed: " + e.getResource() + ": " + e.getStatusLine(), e);
        }
    }

    //--


    public String getOriginOrUnknown() throws IOException {
        FileNode dir;

        dir = world.getWorking(); // TODO
        do {
            if (dir.join(".git").isDirectory()) {
                return "git:" + git(dir, "config", "--get", "remote.origin.url").exec().trim();
            }
            dir = dir.getParent();
        } while (dir != null);
        return "unknown";
    }

    private static Launcher git(FileNode cwd, String... args) {
        Launcher launcher;

        launcher = new Launcher(cwd, "git");
        launcher.arg(args);
        return launcher;
    }

    //--

    private void initContext(FileNode dest) throws IOException, MojoFailureException {
        Node<?> src;

        src = world.resource(dockerbuild);
        if (!src.isDirectory()) {
            throw new MojoFailureException("dockerbuild not found: " + dockerbuild);
        }
        if (dest.isDirectory()) {
            dest.deleteTree();
        }
        dest.mkdirOpt();
        src.copyDirectory(dest);
    }

    //--

    public String build(Daemon daemon, FileNode context) throws IOException, MojoFailureException, MojoExecutionException {
        Log log;
        long started;
        String repositoryTag;

        log = getLog();
        started = System.currentTimeMillis();
        log.info("building image for " + toString());

        repositoryTag = sanitize(image); // TODO: resolve
        log.info("building " + repositoryTag);
        doBuild(log, daemon, context, repositoryTag, getOriginOrUnknown());

        log.info("done: image " + repositoryTag + " (" + (System.currentTimeMillis() - started) / 1000 + " seconds)");
        return repositoryTag;
    }

    private static String sanitize(String str) {
        StringBuilder result;
        char c;

        result = new StringBuilder(str.length());
        for (int i = 0, max = str.length(); i < max; i++) {
            c = str.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || (c == '_') || (c == '-')) {
                result.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                result.append(Character.toLowerCase(c));
            } else {
                // skip
            }
        }
        return result.toString();
    }

    private static boolean asIs(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private void doBuild(Log log, Daemon engine, FileNode context, String repositoryTag, String originScm) throws MojoFailureException, IOException, MojoExecutionException {
        Map<String, BuildArgument> formals;
        Map<String, String> actuals;
        StringWriter output;
        String id;

        initContext(context);
        formals = BuildArgument.scan(context.join("Dockerfile"));
        actuals = buildArgs(formals, context);
        output = new StringWriter();
        try {
            id = engine.imageBuild(repositoryTag, actuals, getLabels(originScm), context, noCache, output);
        } catch (BuildError e) {
            log.error("build failed: " + e.error);
            log.error("build output:");
            log.error(e.output);
            throw new MojoFailureException("build failed");
        } finally {
            output.close();
        }
        log.debug("build image " + id);
        log.debug(output.toString());
    }

    private Map<String, String> getLabels(String originScm) {
        Map<String, String> labels;

        labels = new HashMap<>();
        labels.put(ImageInfo.IMAGE_LABEL_COMMENT, comment);
        labels.put(ImageInfo.IMAGE_LABEL_ORIGIN_SCM, originScm);
        labels.put(ImageInfo.IMAGE_LABEL_ORIGIN_USER, originUser());
        return labels;
    }

    private static String originUser() {
        try {
            return System.getProperty("user.name") + '@' + InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException e) {
            return "unknown host: " + e.getMessage();
        }
    }

    /** compute build argument values and add artifactArguments to context. */
    private Map<String, String> buildArgs(Map<String, BuildArgument> formals, FileNode context) throws MojoFailureException, IOException {
        final String artifactPrefix = "artifact";
        Map<String, String> result;
        String property;
        FileNode src;
        FileNode dest;
        String type;

        result = new HashMap<>();
        for (BuildArgument arg : formals.values()) {
            if (arg.name.startsWith(artifactPrefix)) {
                type = arg.name.substring(artifactPrefix.length()).toLowerCase();
                src = world.file(buildDirectory).join(buildFinalName + "." + type);
                src.checkFile();
                dest = context.join(src.getName());
                src.copyFile(dest);
                result.put(arg.name, dest.getName());
                getLog().info("adding artifact " + dest.getName());
            } else {
                result.put(arg.name, arg.dflt);
            }
        }
        for (Map.Entry<String, String> entry : arguments.entrySet()) {
            property = entry.getKey();
            if (!result.containsKey(property)) {
                throw new MojoFailureException("unknown build argument: " + property + "\n" + available(formals.values()));
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
