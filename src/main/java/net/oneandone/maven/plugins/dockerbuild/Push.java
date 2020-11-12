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
import com.github.dockerjava.api.command.PushImageCmd;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.core.command.PushImageResultCallback;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.oneandone.sushi.launcher.Failure;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;

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
        String name;
        String tag;
        int idx;
        String registry;
        PushImageCmd pushCmd;

        image = imageFile().readString().trim();
        getLog().info("docker push " + image);
        idx = image.indexOf('/');
        if (idx == -1) {
            registry = "";
        } else {
            registry = image.substring(0, idx);
        }
        idx = image.lastIndexOf(":");
        if (idx == -1) {
            name = image;
            tag = null;
        } else {
            name = image.substring(0, idx);
            tag = image.substring(idx + 1);
        }
        pushCmd = docker.pushImageCmd(name).withName(name).withTag(tag);
        pushCmd.withAuthConfig(authConfig(registry));
        try {
            pushCmd.exec(new PushImageResultCallback()).awaitCompletion();
        } catch (InterruptedException e) {
            throw new MojoFailureException("push interrupted", e);
        }
    }

    private AuthConfig authConfig(String registry) throws MojoFailureException {
        Writer output = new StringWriter();
        Reader input = new StringReader(registry);
        JsonObject json;
        AuthConfig auth;

        // TODO: macos only ...
        try {
            world.getWorking().launcher("docker-credential-osxkeychain", "get").exec(output, null, true, input, false);
        } catch (Failure failure) {
            throw new MojoFailureException("cannot access docker credentials: " + failure, failure);
        }
        json = JsonParser.parseReader(new StringReader(output.toString())).getAsJsonObject();
        getLog().info("json: " + json);
        auth = new AuthConfig();
        auth.withUsername(get(json, "Username"));
        auth.withPassword(get(json, "Secret"));
        return auth;
    }

    private static String get(JsonObject obj, String field) {
        JsonElement e;

        e = obj.get(field);
        if (e == null) {
            throw new IllegalStateException("missing field: " + field);
        }
        return e.getAsString();
    }
}
