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
import java.util.List;
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

    public void addFiles(List<FileNode> srcdirs, FileNode destdir, MavenFileFilter filter, MavenProject project, MavenSession session) throws MojoExecutionException, IOException {
        final String filePrefix = "file";
        String key;
        Map<String, FileNode> index;
        FileNode srcfile;
        FileNode destfile;

        index = null;
        for (BuildArgument arg : formals.values()) {
            if (arg.name.startsWith(filePrefix)) {
                key = arg.name.substring(filePrefix.length()).toLowerCase();
                if (key.isEmpty()) {
                    throw new MojoExecutionException("missing file name after prefix: " + arg.name);
                }
                if (index == null) {
                    index = scan(srcdirs);
                }
                srcfile = index.get(key);
                if (srcfile == null) {
                    throw new MojoExecutionException(key + ": file not found in " + srcdirs);
                }
                destfile = destdir.join(key);
                try {
                    filter.copyFile(srcfile.toPath().toFile(), destfile.toPath().toFile(), true, project,
                            new ArrayList<>(), false, "utf8", session);
                } catch (MavenFilteringException e) {
                    throw new MojoExecutionException(e.getMessage(), e);
                }
                result.put(arg.name, Base64.getEncoder().encodeToString(destfile.readBytes()));
            }
        }
    }

    private static Map<String, FileNode> scan(List<FileNode> roots) throws IOException {
        Map<String, FileNode> result;

        result = new HashMap<>();
        for (FileNode root : roots) {
            if (root.exists()) {
                for (FileNode file : root.find("**/*")) {
                    if (file.isFile()) {
                        result.put(normalize(file.getRelative(root)), file);
                    }
                }
            }
        }
        return result;
    }

    private static String normalize(String path) {
        StringBuilder result;
        char c;

        result = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            c = path.charAt(i);
            if (c == '.' || c == '/' || c == '-' || c == '_') {
                continue;
            }
            c = Character.toLowerCase(c);
            result.append(c);
        }
        return result.toString();
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

    public void addExplicit(Map<String, String> arguments) throws MojoExecutionException {
        String name;

        for (Map.Entry<String, String> entry : arguments.entrySet()) {
            name = entry.getKey();
            if (!formals.containsKey(name)) {
                throw new MojoExecutionException("unknown argument: " + name + "\n" + available(formals.values()));
            }
            result.put(name, eval(entry.getValue()));
        }
    }

    private String eval(String value) throws MojoExecutionException {
        int idx;
        String name;

        if (value.startsWith("%")) {
            idx = value.indexOf(':');
            if (idx == -1) {
                throw new MojoExecutionException("invalid value: " + value);
            }
            name = value.substring(1, idx);
            value = value.substring(idx + 1);
            switch (name) {
                case "base64":
                    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
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
