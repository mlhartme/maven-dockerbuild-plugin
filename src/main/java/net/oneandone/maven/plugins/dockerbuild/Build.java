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
 * Builds a Docker image and pushes it.
 */
@Mojo(name = "build", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.NONE, threadSafe = true)
public class Build extends AbstractMojo {
    private final World world;

    /** Don't use Docker cache */
    @Parameter(defaultValue = "false")
    private final boolean noCache;

    @Parameter(property = "docker.repository",
            defaultValue = "contargo.server.lan/cisoops-public/${project.groupId}-${project.artifactId}") // TODO
    private final String repository;

    /** Explicit comment to add to image */
    @Parameter(defaultValue = "")
    private final String comment;

    /** Dockerfile argument */
    @Parameter
    private Map<String, String> arguments;

    /** Where to build the context directory */
    @Parameter(defaultValue = "${project.build.directory}/dockerbuild")
    private String contextDir;

    public Build() throws IOException {
        this(World.create());
    }

    public Build(World world) {
        this.world = world;
        this.noCache = false;
        this.repository = "";
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

    public void build() throws IOException, MojoFailureException {
        try (Daemon daemon = Daemon.create()) {
            build(daemon, world.file(contextDir));
        } catch (StatusException e) {
            throw new MojoFailureException("docker build failed: " + e.getResource() + ": " + e.getStatusLine(), e);
        }
    }

    //--


    // TODO
    public FileNode templates() {
        return world.file("/Users/mhm/Projects/bitbucket.1and1.org/cisodevenv/dockerbuild-library");
    }


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

    public void createContext(FileNode context) throws IOException {
        FileNode template;
        FileNode destparent;
        FileNode destfile;

        template = templates().join("vanilla-war").checkDirectory(); // TODO
        if (context.isFile()) {
            context.deleteTree();
        }
        context.mkdirOpt();
// TODO        war.copyFile(context.join("app.war"));
        for (FileNode srcfile : template.find("**/*")) {
            if (srcfile.isDirectory()) {
                continue;
            }
            destfile = context.join(srcfile.getRelative(template));
            destparent = destfile.getParent();
            destparent.mkdirsOpt();
            srcfile.copy(destfile);
        }
    }

    //--

    public String build(Daemon daemon, FileNode context) throws IOException, MojoFailureException {
        Log log;
        long started;
        int tag;
        String repositoryTag;

        log = getLog();
        started = System.currentTimeMillis();
        log.info("building image for " + toString());
        tag = nextTag(daemon);
        repositoryTag = repository + ":" + tag;

        doBuild(log, daemon, context, repositoryTag, getOriginOrUnknown());

        log.info("pushing ...");
        log.info(daemon.imagePush(repositoryTag));
        log.info("done: image " + tag + " (" + (System.currentTimeMillis() - started) / 1000 + " seconds)");
        return repositoryTag;
    }

    private void doBuild(Log log, Daemon engine, FileNode context, String repositoryTag, String originScm) throws MojoFailureException, IOException {
        Map<String, String> buildArgs;
        StringWriter output;
        String image;

        createContext(context);
        buildArgs = buildArgs(BuildArgument.scan(context.join("Dockerfile")));
        output = new StringWriter();
        try {
            image = engine.imageBuild(repositoryTag, buildArgs,
                    getLabels(originScm, buildArgs), context, noCache, output);
        } catch (BuildError e) {
            log.error("build failed: " + e.error);
            log.error("build output:");
            log.error(e.output);
            throw new MojoFailureException("build failed");
        } finally {
            output.close();
        }
        log.debug("successfully built image: " + image);
        log.debug(output.toString());
    }

    private Map<String, String> getLabels(String originScm, Map<String, String> buildArgs) {
        Map<String, String> labels;

        labels = new HashMap<>();
        labels.put(ImageInfo.IMAGE_LABEL_COMMENT, comment);
        labels.put(ImageInfo.IMAGE_LABEL_ORIGIN_SCM, originScm);
        labels.put(ImageInfo.IMAGE_LABEL_ORIGIN_USER, originUser());
        for (Map.Entry<String, String> arg : buildArgs.entrySet()) {
            labels.put(ImageInfo.IMAGE_LABEL_ARG_PREFIX + arg.getKey(), arg.getValue());
        }
        return labels;
    }

    private static String originUser() {
        try {
            return System.getProperty("user.name") + '@' + InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException e) {
            return "unknown host: " + e.getMessage();
        }
    }

    /** @return next version */
    public int nextTag(Daemon docker) throws IOException {
        Map<String, ImageInfo> images;

        images = repositoryTags(docker.imageList());
        return nextTag(images.keySet());
    }

    public static String tag(String repositoryTag) {
        String result;
        int idx;

        result = repositoryTag;
        idx = result.lastIndexOf(':');
        if (idx == -1) {
            throw new IllegalArgumentException(result);
        }
        return result.substring(idx + 1);
    }

    public static int nextTag(Collection<String> repositoryTags) {
        String tag;
        int number;
        int max;

        max = 0;
        for (String repoTag : repositoryTags) {
            tag = tag(repoTag);
            try {
                number = Integer.parseInt(tag);
                if (number > max) {
                    max = number;
                }
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        return max + 1;
    }

    public Map<String, ImageInfo> repositoryTags(Map<String, ImageInfo> imageMap) {
        Map<String, ImageInfo> result;
        ImageInfo info;

        result = new HashMap<>();
        for (Map.Entry<String, ImageInfo> entry : imageMap.entrySet()) {
            info = entry.getValue();
            for (String repositoryTag : info.repositoryTags) {
                if (repositoryTag.startsWith(repository + ":")) {
                    result.put(repositoryTag, info);
                }
            }
        }
        return result;
    }

    protected static String eat(Map<String, String> arguments, String key, String dflt) {
        String explicitValue;

        explicitValue = arguments.remove(key);
        return explicitValue != null ? explicitValue : dflt;
    }

    private Map<String, String> buildArgs(Map<String, BuildArgument> defaults) throws MojoFailureException {
        Map<String, String> result;
        String property;

        result = new HashMap<>();
        for (BuildArgument arg : defaults.values()) {
            result.put(arg.name, arg.dflt);
        }
        for (Map.Entry<String, String> entry : arguments.entrySet()) {
            property = entry.getKey();
            if (!result.containsKey(property)) {
                throw new MojoFailureException("unknown build argument: " + property + "\n" + available(defaults.values()));
            }
            result.put(property, entry.getValue());
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
