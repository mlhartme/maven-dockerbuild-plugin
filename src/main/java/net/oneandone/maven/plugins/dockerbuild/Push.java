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
import com.github.dockerjava.api.async.ResultCallback;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.IOException;

/**
 * Push Docker image.
 */
@Mojo(name = "push", defaultPhase = LifecyclePhase.DEPLOY, requiresDependencyResolution = ResolutionScope.NONE, threadSafe = true)
public class Push extends Base {
    public Push() throws IOException {
    }

    @Override
    public void doExecute(DockerClient docker) throws IOException, MojoFailureException {
        String image;

        try {
            image = imageFile().readString().trim();
            docker.pushImageCmd(image).exec(new ResultCallback.Adapter<>()).awaitCompletion();
            getLog().info("pushed " + image);
        } catch (InterruptedException e) {
            throw new MojoFailureException("push interrupted", e);
        }
    }
}
