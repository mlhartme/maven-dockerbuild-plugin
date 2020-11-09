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

import net.oneandone.stool.docker.Daemon;
import net.oneandone.sushi.fs.World;
import net.oneandone.sushi.fs.http.StatusException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds a Docker image and pushes it.
 */
@Mojo(name = "build", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.NONE, threadSafe = true)
public class Build extends AbstractMojo {
    private final World world;

    /** Don't use Docker cache */
    @Parameter(defaultValue = "false")
    private final boolean noCache;

    @Parameter(property = "docker.repository",
            defaultValue = "contargo.server.lan/cisoops-public/${project.groupId}-${project.artifactId}") // TODO
    private final String repository;

    /**
     * Specifies the artifact to add to the Docker context.
     */
    @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}.war")
    private final String artifact;

    /** Explicit comment to add to image */
    @Parameter(defaultValue = "")
    private final String comment;

    /** Dockerfile argument */
    @Parameter
    private Map<String, String> arguments;

    public Build() throws IOException {
        this(World.create());
    }

    public Build(World world) {
        this.world = world;
        this.noCache = false;
        this.repository = "";
        this.artifact = null;
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
    public void build() throws IOException, MojoFailureException {
        Source source;

        source = new Source(getLog(), world.getWorking().join("target/dockerbuild") /* TODO */, world.file(artifact).checkFile());
        try (Daemon daemon = Daemon.create()) {
            source.build(daemon, repository, comment, noCache, arguments);
        } catch (StatusException e) {
            throw new MojoFailureException("docker build failed: " + e.getResource() + ": " + e.getStatusLine(), e);
        }
    }

}
