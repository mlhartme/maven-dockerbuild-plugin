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

import net.oneandone.sushi.fs.World;
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
    private final World world;
    private final FileNode context;
    private final Map<String, BuildArgument> formals;
    private final Log log;
    private final MavenProject project;
    private final String comment;
    private final Map<String, String> arguments;

    public static Arguments create(Context context, Log log, MavenProject project, String comment, Map<String, String> arguments) throws IOException {
        Map<String, BuildArgument> formals;

        formals = BuildArgument.scan(context.dockerfile());
        return new Arguments(context, formals, log, project, comment, arguments);

    }

    public Arguments(Context context, Map<String, BuildArgument> formals, Log log, MavenProject project, String comment, Map<String, String> arguments) {
        this.context = context.getDirectory();
        this.formals = formals;
        this.world = this.context.getWorld();
        this.log = log;
        this.project = project;
        this.comment = comment;
        this.arguments = arguments;
    }

    /** compute build argument values and add artifactArguments to context. */
    public Map<String, String> buildArgs() throws MojoExecutionException, IOException {
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
                src = world.file(project.getBuild().getDirectory()).join(project.getBuild().getFinalName() + "." + type);
                src.checkFile();
                dest = context.join(src.getName());
                src.copyFile(dest);
                result.put(arg.name, dest.getName());
                log.info("cp " + src + " " + dest);
            } else if (arg.name.startsWith(pomPrefix)) {
                switch (arg.name) {
                    case "pomScm":
                        result.put(arg.name, getScm());
                        break;
                    default:
                        throw new MojoExecutionException("unknown pom argument: " + arg.name);
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
                        throw new MojoExecutionException("unknown build argument: " + arg.name);
                }
            } else {
                result.put(arg.name, arg.dflt);
            }
        }
        for (Map.Entry<String, String> entry : arguments.entrySet()) {
            property = entry.getKey();
            if (!result.containsKey(property)) {
                throw new MojoExecutionException("unknown argument: " + property + "\n" + available(formals.values()));
            }
            result.put(property, entry.getValue());
        }
        for (Map.Entry<String, String> entry : result.entrySet()) {
            if (entry.getValue() == null) {
                throw new MojoExecutionException("mandatory argument is missing: " + entry.getKey());
            }
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

    private String getScm() throws MojoExecutionException {
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
