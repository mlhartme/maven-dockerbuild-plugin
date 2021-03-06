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

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.BuildResponseItem;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class BuildListener implements ResultCallback<BuildResponseItem> {
    private final Log log;
    private final PrintWriter logfile;
    private List<Throwable> errors;

    private final CountDownLatch completed = new CountDownLatch(1);

    private String imageId;
    private String error;
    private Closeable stream;
    private boolean closed = false;

    public BuildListener(Log log, PrintWriter logfile) {
        this.log = log;
        this.logfile = logfile;
        this.errors = new ArrayList<>();
    }

    @Override
    public void onStart(Closeable theStream) {
        this.stream = theStream;
        this.closed = false;
    }

    @Override
    public void onNext(BuildResponseItem item) {
        String st;

        st = item.getStream();
        if (st != null) {
            logfile.print(st);
        }
        if (item.isBuildSuccessIndicated()) {
            this.imageId = item.getImageId();
        } else if (item.isErrorIndicated()) {
            this.error = item.getError();
        }
        log.debug(item.toString());
    }

    @Override
    public void onError(Throwable throwable) {
        if (closed) {
            return;
        }
        errors.add(throwable);
        try {
            close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onComplete() {
        try {
            close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    //--

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            try {
                if (stream != null) {
                    stream.close();
                }
            } finally {
                completed.countDown();
            }
        }
    }

    //--

    public String awaitImageId() throws MojoExecutionException {
        MojoExecutionException exception;

        try {
            completed.await();
        } catch (InterruptedException e) {
            throw new MojoExecutionException("interrupted", e);
        } finally {
            try {
                close();
            } catch (IOException e) {
                log.error("closed failed: ");
                log.error(e);
            }
        }
        if (!errors.isEmpty()) {
            exception = new MojoExecutionException("exception(s) processing response stream");
            for (Throwable th : errors) {
                exception.initCause(th);
            }
            throw exception;
        }
        if (imageId != null) {
            return imageId;
        } else {
            throw new MojoExecutionException("Docker build failed: " + error); // error may be null
        }
    }
}
