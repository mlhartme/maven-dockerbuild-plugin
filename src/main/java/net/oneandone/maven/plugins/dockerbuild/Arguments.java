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

import net.oneandone.sushi.fs.file.FileNode;
import org.apache.maven.model.Scm;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** represents the actual arguments passed to the docker build */
public class Arguments {
    private final Map<String, BuildArgument> formals;
    private final Log log;
    private final Map<String, String> result;

    public static Arguments create(FileNode dockerfile, Log log) throws IOException {
        return new Arguments(BuildArgument.scan(dockerfile), log);

    }

    public Arguments(Map<String, BuildArgument> formals, Log log) {
        this.formals = formals;
        this.log = log;
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

    public void addPom(MavenProject project) throws MojoExecutionException {
        final String pomPrefix = "pom";

        for (BuildArgument arg : formals.values()) {
            if (arg.name.startsWith(pomPrefix)) {
                switch (arg.name) {
                    case "pomScm":
                        result.put(arg.name, getScm(project));
                        break;
                    default:
                        throw new MojoExecutionException("unknown pom argument: " + arg.name);
                }
            }
        }
    }

    public void addBuild(String comment) throws MojoExecutionException {
        final String buildPrefix = "build";

        for (BuildArgument arg : formals.values()) {
            if (arg.name.startsWith(buildPrefix)) {
                switch (arg.name) {
                    case "buildOrigin":
                        result.put(arg.name, origin());
                        break;
                    case "buildComment":
                        result.put(arg.name, comment);
                        break;
                    default:
                        throw new MojoExecutionException("unknown build argument: " + arg.name);
                }
            }
        }
    }

    public void addExplicit(Map<String, String> arguments) throws MojoExecutionException {
        String name;

        for (Map.Entry<String, String> entry : arguments.entrySet()) {
            name = entry.getKey();
            if (!formals.containsKey(name)) {
                throw new MojoExecutionException("unknown argument: " + name + "\n" + available(formals.values()));
            }
            result.put(name, entry.getValue());
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

    private static String origin() {
        try {
            return System.getProperty("user.name") + '@' + InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException e) {
            return "unknown host: " + e.getMessage();
        }
    }

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
