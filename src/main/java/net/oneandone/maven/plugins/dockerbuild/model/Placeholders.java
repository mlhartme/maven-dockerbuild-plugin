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
import net.oneandone.sushi.launcher.Failure;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

import java.text.SimpleDateFormat;
import java.util.Date;

/** Version placeholders. Inspired by https://maven.fabric8.io/#image-name-placeholders */
public class Placeholders {
    private final FileNode working;
    private final MavenProject project;

    public Placeholders(FileNode working, MavenProject project) {
        this.working = working;
        this.project = project;
    }

    public String resolve(String str) throws MojoExecutionException {
        char c;
        StringBuilder result;
        String prefix;

        result = new StringBuilder();
        for (int i = 0, max = str.length(); i < max; i++) {
            c = str.charAt(i);
            if (c != '%') {
                result.append(c);
            } else {
                if (i + 1 >= max) {
                    throw new MojoExecutionException("invalid placeholder: " + str);
                }
                i++;
                if (str.charAt(i) == '-') {
                    prefix = "-";
                    if (i + 1 >= max) {
                        throw new MojoExecutionException("invalid placeholder: " + str);
                    }
                    i++;
                } else {
                    prefix = "";
                }
                switch (str.charAt(i)) {
                    case 'a':
                        append(result, prefix, artifact());
                        break;
                    case 'b':
                        append(result, prefix, branch());
                        break;
                    case 'g':
                        append(result, prefix, group());
                        break;
                    case 'V':
                        append(result, prefix, version());
                        break;
                    default:
                        throw new MojoExecutionException("unknown placeholder: " + str);
                }
            }
        }
        return result.toString();
    }

    private void append(StringBuilder dest, String prefix, String expanded) {
        if (expanded.isEmpty()) {
            return;
        }
        dest.append(prefix);
        dest.append(expanded);
    }

    private String branch() throws MojoExecutionException {
        return isSnapshot() ? gitBanch() : "";
    }

    private String gitBanch() throws MojoExecutionException {
        try {
            return sanitize(working.exec("git", "symbolic-ref", "--short", "-q", "HEAD").trim());
        } catch (Failure e) {
            throw new MojoExecutionException("cannot determine current branch: " + e.getMessage(), e);
        }
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

    private static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";

    private boolean isSnapshot() {
        return project.getVersion().endsWith(SNAPSHOT_SUFFIX);
    }

    // CAUTION: this version differs from fabric8 placeholder
    private String version() {
        String str;

        str = project.getVersion();
        if (str.endsWith(SNAPSHOT_SUFFIX)) {
            str = str.substring(0, str.length() - SNAPSHOT_SUFFIX.length() + 1);
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
}
