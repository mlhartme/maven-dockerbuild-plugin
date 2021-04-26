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
import org.apache.maven.model.Scm;
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
import java.util.HashMap;
import java.util.Map;

/** represents the actual arguments passed to the docker build */
public class Arguments {
    private final Log log;
    private final Map<String, BuildArgument> formals;
    private final Map<String, String> result;

    public Arguments(Log log, Map<String, BuildArgument> formals) {
        this.log = log;
        this.formals = formals;
        this.result = new HashMap<>();
    }

    public Map<String, String> run(Map<String, String> arguments, Context context, FileNode directory, String artifactName,
                    World world, MavenFileFilter filter, MavenProject project, MavenSession session)
            throws MojoExecutionException, IOException {
        String name;

        for (Map.Entry<String, String> entry : arguments.entrySet()) {
            name = entry.getKey();
            if (!formals.containsKey(name)) {
                throw new MojoExecutionException("unknown argument: " + name + "\n" + available(formals.values()));
            }
            result.put(name, eval(entry.getValue(), context, directory, artifactName, world, filter, project, session));
        }
        return result();
    }

    private String eval(String value, Context context, FileNode directory, String artifactName,
                        World world, MavenFileFilter filter, MavenProject project, MavenSession session)
            throws MojoExecutionException, IOException {
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
        value = eval(value.substring(idx + 1), context, directory, artifactName, world, filter, project, session);
        switch (name) {
            case "base64":
                return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
            case "file":
                return file(value, world, filter, project, session);
            case "artifact":
                return artifact(value, context, directory, artifactName);
            default:
                throw new MojoExecutionException("unknown directive: " + name);
        }
    }

    private String file(String value, World world, MavenFileFilter filter, MavenProject project, MavenSession session) throws IOException, MojoExecutionException {
        FileNode src;
        FileNode dest;

        src = world.file(value);
        dest = world.getTemp().createTempFile();
        try {
            filter.copyFile(src.toPath().toFile(), dest.toPath().toFile(), true, project,
                    new ArrayList<>(), false, "utf8", session);
            return dest.readString();
        } catch (MavenFilteringException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } finally {
            dest.deleteFile();
        }
    }

    private String artifact(String extension, Context context, FileNode directory, String artifactName) throws IOException {
        FileNode src;
        FileNode dest;

        src = directory.join(artifactName + "." + extension);
        src.checkFile();
        dest = context.getDirectory().join(src.getName());
        src.copyFile(dest);
        log.info("cp " + src + " " + dest);
        return src.getName();
    }


    public Map<String, String> result() throws MojoExecutionException {
        for (BuildArgument arg : formals.values()) {
            if (!result.containsKey(arg.name)) {
                result.put(arg.name, arg.dflt);
            }
        }
        for (Map.Entry<String, String> entry : result.entrySet()) {
            if (entry.getValue() == null) {
                throw new MojoExecutionException("mandatory argument is missing: " + entry.getKey());
            }
        }
        return result;
    }

    //--

    private String getScm(MavenProject project) throws MojoExecutionException {
        Scm scm;
        String str;

        scm = project.getScm();
        str = scm.getDeveloperConnection();
        if (str != null) {
            return str;
        }
        str = scm.getConnection();
        if (str != null) {
            return str;
        }
        throw new MojoExecutionException("pomScm argument: scm is not defined in this project");
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
