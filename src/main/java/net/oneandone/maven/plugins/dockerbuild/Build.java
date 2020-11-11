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
import net.oneandone.sushi.fs.Node;
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
import org.kamranzafar.jtar.TarEntry;
import org.kamranzafar.jtar.TarHeader;
import org.kamranzafar.jtar.TarOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
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
        String repositoryTag;
        FileNode context;
        Log log;
        long started;
        Map<String, BuildArgument> formals;
        Map<String, String> actuals;
        String id;
        BuildImageCmd build;
        StringBuilder cli;
        FileNode buildLog;

        repositoryTag = resolve(image); // TODO: resolve
        log = getLog();
        context = context();
        buildLog = buildLog();
        buildLog.getParent().mkdirsOpt();
        started = System.currentTimeMillis();
        log.info("extracting dockerbuild " + dockerbuild + " to " + context);
        initContext();
        imageFile().getParent().mkdirsOpt();
        imageFile().writeString(repositoryTag);
        formals = BuildArgument.scan(context.join("Dockerfile"));
        actuals = buildArgs(formals);
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

    private void initContext() throws IOException, MojoFailureException {
        FileNode dest;
        Node<?> src;

        src = world.resource(dockerbuild);
        dest = context();
        if (!src.isDirectory()) {
            throw new MojoFailureException("dockerbuild not found: " + dockerbuild);
        }
        if (dest.isDirectory()) {
            dest.deleteTree();
        }
        dest.mkdirsOpt();
        src.copyDirectory(dest);
    }

    //--

    /** inspired by https://maven.fabric8.io/#image-name-placeholders */
    private String resolve(String str) throws MojoFailureException {
        char c;
        StringBuilder result;

        result = new StringBuilder();
        for (int i = 0, max = str.length(); i < max; i++) {
            c = str.charAt(i);
            if (c == '%') {
                if (i + 1 >= max) {
                    throw new MojoFailureException("invalid placeholder: " + str);
                }
                i++;
                switch (str.charAt(i)) {
                    case 'a':
                        result.append(artifact());
                        break;
                    case 'g':
                        result.append(group());
                        break;
                    case 'V':
                        result.append(version());
                        break;
                    default:
                        throw new MojoFailureException("unknown placeholder: " + str);
                }
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private String artifact() {
        return sanitize(project.getArtifactId());
    }

    private String group() {
        String str;
        int idx;

        str = project.getGroupId();
        idx = str.lastIndexOf('.');
        if (idx != -1) {
            str = str.substring(idx + 1);
        }
        return sanitize(str);
    }

    // CAUTION: this version differs from fabric8 placeholder
    private String version() {
        final String suffix = "-SNAPSHOT";
        String str;

        str = project.getVersion();
        if (str.endsWith(suffix)) {
            str = str.substring(0, str.length() - suffix.length() + 1);
            str = str + timestamp();
        }
        return sanitize(str);
    }

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS");

    private static String timestamp() {
        return FMT.format(new Date());
    }

    private static String sanitize(String str) {
        StringBuilder result;
        char c;

        result = new StringBuilder(str.length());
        for (int i = 0, max = str.length(); i < max; i++) {
            c = str.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || (c == '_') || (c == '-') || (c == '.')) {
                result.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                result.append(Character.toLowerCase(c));
            } else {
                // skip
            }
        }
        return result.toString();
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
