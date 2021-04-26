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

    /** compute build argument values and add artifactArguments to context. */
    public void addArtifacts(Context context, FileNode directory, String artifactName) throws IOException {
        final String artifactPrefix = "artifact";
        FileNode src;
        FileNode dest;
        String extension;

        for (BuildArgument arg : formals.values()) {
            if (arg.name.startsWith(artifactPrefix)) {
                extension = arg.name.substring(artifactPrefix.length()).toLowerCase();
                src = directory.join(artifactName + "." + extension);
                src.checkFile();
                dest = context.getDirectory().join(src.getName());
                src.copyFile(dest);
                result.put(arg.name, dest.getName());
                log.info("cp " + src + " " + dest);
            }
        }
    }

    public void addExplicit(Map<String, String> arguments, World world, MavenFileFilter filter, MavenProject project, MavenSession session)
            throws MojoExecutionException, IOException {
        String name;

        for (Map.Entry<String, String> entry : arguments.entrySet()) {
            name = entry.getKey();
            if (!formals.containsKey(name)) {
                throw new MojoExecutionException("unknown argument: " + name + "\n" + available(formals.values()));
            }
            result.put(name, eval(entry.getValue(), world, filter, project, session));
        }
    }

    private String eval(String value, World world, MavenFileFilter filter, MavenProject project, MavenSession session)
            throws MojoExecutionException, IOException {
        int idx;
        String name;
        FileNode srcfile;
        FileNode destfile;
        if (value.startsWith("%")) {
            idx = value.indexOf(':');
            if (idx == -1) {
                throw new MojoExecutionException("invalid value: " + value);
            }
            name = value.substring(1, idx);
            value = eval(value.substring(idx + 1), world, filter, project, session);
            switch (name) {
                case "base64":
                    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
                case "file":
                    srcfile = world.file(value);
                    destfile = world.getTemp().createTempFile();
                    try {
                        filter.copyFile(srcfile.toPath().toFile(), destfile.toPath().toFile(), true, project,
                                new ArrayList<>(), false, "utf8", session);
                        return destfile.readString();
                    } catch (MavenFilteringException e) {
                        throw new MojoExecutionException(e.getMessage(), e);
                    } finally {
                        destfile.deleteFile();
                    }
                default:
                    throw new MojoExecutionException("unknown directive: " + name);
            }
        } else {
            return value;
        }
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
