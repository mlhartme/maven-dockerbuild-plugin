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
import net.oneandone.maven.plugins.dockerbuild.model.Placeholders;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Builds a Docker image for this Maven module.
 */
@Mojo(name = "properties", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.NONE, threadSafe = true)
public class Properties extends Base {
    /** Image tag created by this build. Provides placeholders inspired by https://maven.fabric8.io/#image-configuration */
    @Parameter(property = "dockerbuild.image", defaultValue = "%g/%a:%V")
    private final String image;

    /** Used internally */
    @Parameter(property = "project", required = true, readonly = true)
    private final MavenProject project;


    public Properties() throws IOException {
        this.image = "";
        this.project = null;
    }

    @Override
    public void doExecute(DockerClient docker) throws MojoExecutionException {
        project.getProperties().put("dockerbuild.image", new Placeholders(world.file(project.getBasedir()), project).resolve(image));
        project.getProperties().put("dockerbuild.origin", origin());
    }

    private static String origin() {
        try {
            return System.getProperty("user.name") + '@' + InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException e) {
            return "unknown host: " + e.getMessage();
        }
    }
}
