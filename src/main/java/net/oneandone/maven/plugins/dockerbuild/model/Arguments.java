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
package net.oneandone.maven.plugins.dockerbuild.model;

import net.oneandone.sushi.fs.World;
import net.oneandone.sushi.fs.file.FileNode;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.filtering.MavenFileFilter;
import org.apache.maven.shared.filtering.MavenFilteringException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** represents the actual arguments passed to the docker build */
public class Arguments {
    private final Log log;
    private final World world;
    private final Map<String, BuildArgument> formals;
    private final Context context;
    private final FileNode target;
    private final MavenFileFilter filter;
    private final MavenProject project;
    private final MavenSession session;

    public Arguments(Log log, Context context, MavenFileFilter filter, MavenProject project, MavenSession session) throws IOException {
        this.log = log;
        this.world = context.getDirectory().getWorld();
        this.formals = context.formals();
        this.context = context;
        this.target = world.file(project.getBuild().getDirectory());
        this.filter = filter;
        this.project = project;
        this.session = session;
    }

    public Map<String, String> eval(Map<String, String> actuals) throws MojoExecutionException, IOException {
        Map<String, String> result;
        String name;

        result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : actuals.entrySet()) {
            name = entry.getKey();
            if (!formals.containsKey(name)) {
                throw new MojoExecutionException("unknown argument: " + name + "\n" + available(formals.values()));
            }
            result.put(name, eval(entry.getValue()));
        }
        for (BuildArgument arg : formals.values()) {
            if (!result.containsKey(arg.name)) {
                if (arg.dflt == null) {
                    throw new MojoExecutionException("mandatory argument is missing: " + arg.name);
                }
            }
        }
        return result;
    }

    private String eval(String value) throws MojoExecutionException, IOException {
        int idx;
        String name;

        if (!value.startsWith("%")) {
            return value;
        }
        idx = value.indexOf(':');
        if (idx == -1) {
            throw new MojoExecutionException("invalid value: " + value);
        }
        name = value.substring(1, idx);
        value = eval(value.substring(idx + 1));
        switch (name) {
            case "artifact":
                return artifact(value);
            case "base64":
                return base64(value);
            case "copy":
                return copy(value);
            case "file":
                return file(value);
            case "filter":
                return filter(value);
            default:
                throw new MojoExecutionException("unknown directive: " + name);
        }
    }

    private String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String file(String value) throws IOException {
        return world.file(value).readString();
    }

    private String filter(String value) throws IOException, MojoExecutionException {
        FileNode src;
        FileNode dest;

        src = world.getTemp().createTempFile();
        src.writeString(value + "\n");
        log.info("src: " + value);
        dest = world.getTemp().createTempFile();
        try {
            filter.copyFile(src.toPath().toFile(), dest.toPath().toFile(), true, project,
                    new ArrayList<>(), false, "utf8", session);
            log.info("dest: " + value);
            return dest.readString();
        } catch (MavenFilteringException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } finally {
            src.deleteFile();
            dest.deleteFile();
        }
    }

    private String artifact(String str) throws IOException {
        int idx;
        String suffix;
        FileNode src;

        idx = str.indexOf(':');
        if (idx < 0) {
            suffix = "." + str;
        } else {
            suffix = "-" + str.substring(0, idx) + "." + str.substring(idx + 1);
        }
        src = target.join(project.getBuild().getFinalName() + suffix);
        src.checkFile();
        return src.getAbsolute();
    }

    private String copy(String path) throws IOException {
        FileNode src;
        FileNode dest;

        src = world.file(path);
        src.checkFile();
        dest = context.getDirectory().join(src.getName());
        src.copyFile(dest);
        log.info("cp " + src + " " + dest);
        return src.getName();
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
