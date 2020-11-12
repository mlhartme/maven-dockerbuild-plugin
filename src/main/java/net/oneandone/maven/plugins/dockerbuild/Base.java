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
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import net.oneandone.sushi.fs.World;
import net.oneandone.sushi.fs.file.FileNode;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;

public abstract class Base extends AbstractMojo {
    protected final World world;

    @Parameter(defaultValue = "${project.build.directory}/dockerbuild")
    private String buildDirectory;

    public Base() throws IOException {
        this(World.create());
    }

    public Base(World world) {
        this.world = world;
    }

    private FileNode buildDirectory() {
        return world.file(buildDirectory);
    }

    protected FileNode context() {
        return buildDirectory().join("context");
    }

    protected FileNode buildLog() {
        return buildDirectory().join("build.log");
    }

    protected FileNode imageFile() {
        return buildDirectory().join("image");
    }

    @Override
    public void execute() throws MojoExecutionException {
        try {
            doExecute();
        } catch (IOException e) {
            throw new MojoExecutionException("io error: " + e.getMessage(), e);
        }
    }

    public void doExecute() throws IOException, MojoExecutionException {
        DefaultDockerClientConfig config;

        config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        try (DockerHttpClient http = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
             DockerClient docker = DockerClientImpl.getInstance(config, http)) {
            initConfigPath(config.getDockerConfigPath());
            doExecute(docker);
        }
    }

    /** override this to get access to the configPath */
    public void initConfigPath(String configPath) {
    }

    public abstract void doExecute(DockerClient docker) throws IOException, MojoExecutionException;
}
