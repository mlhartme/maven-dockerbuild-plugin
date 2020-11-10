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
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
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
import org.kamranzafar.jtar.TarEntry;
import org.kamranzafar.jtar.TarHeader;
import org.kamranzafar.jtar.TarOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
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
        DockerClientConfig config;
        String repositoryTag;

        config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        try (DockerHttpClient http = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
             DockerClient docker = DockerClientImpl.getInstance(config, http)) {
            repositoryTag = sanitize(image); // TODO: resolve
            doBuild(docker, world.file(buildDirectory).join("dockerbuild"), repositoryTag, getOriginOrUnknown());
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

    private void doBuild(DockerClient docker, FileNode context, String repositoryTag, String originScm) throws MojoFailureException, IOException, MojoExecutionException {
        Log log;
        long started;
        Map<String, BuildArgument> formals;
        Map<String, String> actuals;
        String id;
        BuildImageCmd build;
        StringBuilder cli;
        FileNode logfileNode;

        log = getLog();
        logfileNode = context.getParent().join(context.getName() + ".log");
        started = System.currentTimeMillis();
        log.info("unpacking dockerbuild " + dockerbuild);
        initContext(context);
        formals = BuildArgument.scan(context.join("Dockerfile"));
        actuals = buildArgs(formals, context);
        try (InputStream tarSrc = tar(context).newInputStream()) {
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
            cli.append(" >" + logfileNode);
            log.info(cli.toString());
            try (PrintWriter logfile = new PrintWriter(logfileNode.newWriter())) {
                id = build.exec(new BuildResults(logfile)).awaitImageId();
            }
        } catch (DockerClientException | DockerException e) {
            log.error("build failed: " + e.getMessage());
            log.debug(e);
            throw new MojoFailureException("build failed");
        }
        log.info("Done: tag=" + repositoryTag + " id=" + id + " seconds=" + (System.currentTimeMillis() - started) / 1000);
    }

    /** tar directory into byte array */
    public static FileNode tar(FileNode directory) throws IOException {
        FileNode result;
        List<FileNode> all;
        TarOutputStream tar;
        byte[] buffer;
        Iterator<FileNode> iter;
        FileNode file;
        int count;
        long now;

        result = directory.getWorld().getTemp().createTempFile();
        buffer = new byte[64 * 1024];
        try (OutputStream dest = result.newOutputStream()) {
            tar = new TarOutputStream(dest);
            now = System.currentTimeMillis();
            all = directory.find("**/*");
            iter = all.iterator();
            while (iter.hasNext()) {
                file = iter.next();
                if (file.isDirectory()) {
                    tar.putNextEntry(new TarEntry(TarHeader.createHeader(file.getRelative(directory), 0, now, true, 0700)));
                    iter.remove();
                }
            }
            iter = all.iterator();
            while (iter.hasNext()) {
                file = iter.next();
                tar.putNextEntry(new TarEntry(TarHeader.createHeader(file.getRelative(directory), file.size(), now, false, 0700)));
                try (InputStream src = file.newInputStream()) {
                    while (true) {
                        count = src.read(buffer);
                        if (count == -1) {
                            break;
                        }
                        tar.write(buffer, 0, count);
                    }
                }
            }
            tar.close();
        } catch (IOException | RuntimeException | Error e) {
            result.deleteFile();
            throw e;
        }
        return result;
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
