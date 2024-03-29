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

import net.oneandone.sushi.fs.Node;
import net.oneandone.sushi.fs.file.FileNode;
import org.apache.maven.plugin.MojoExecutionException;
import org.kamranzafar.jtar.TarEntry;
import org.kamranzafar.jtar.TarHeader;
import org.kamranzafar.jtar.TarOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Manage Docker build context directory */
public class Context {
    public static Context create(FileNode srcJar, String dockerbuild, FileNode dest) throws IOException, MojoExecutionException {
        Node<?> src;

        src = srcJar.openJar().checkDirectory();
        if (!src.isDirectory()) {
            throw new MojoExecutionException("dockerbuild not found: " + dockerbuild);
        }
        if (dest.isDirectory()) {
            dest.deleteTree();
        }
        dest.mkdirsOpt();
        src.copyDirectory(dest, src.getWorld().filter().includeAll().exclude("META-INF", "META-INF/**/*"));
        return new Context(dest);
    }

    private final FileNode directory;

    private Context(FileNode directory) {
        this.directory = directory;
    }

    public FileNode getDirectory() { // TODO
        return directory;
    }

    /** tar directory into byte array */
    public FileNode tar() throws IOException {
        FileNode result;
        List<FileNode> all;
        TarOutputStream tar;
        byte[] buffer;
        Iterator<FileNode> iter;
        FileNode file;
        int count;
        long now;

        result = directory.getWorld().getTemp().createTempFile();
        buffer = new byte[64 * 1024];
        try (OutputStream dest = result.newOutputStream()) {
            tar = new TarOutputStream(dest);
            now = System.currentTimeMillis();
            all = directory.find("**/*");
            iter = all.iterator();
            while (iter.hasNext()) {
                file = iter.next();
                if (file.isDirectory()) {
                    tar.putNextEntry(new TarEntry(TarHeader.createHeader(file.getRelative(directory), 0, now, true, 0700)));
                    iter.remove();
                }
            }
            iter = all.iterator();
            while (iter.hasNext()) {
                file = iter.next();
                tar.putNextEntry(new TarEntry(TarHeader.createHeader(file.getRelative(directory), file.size(), now, false, 0700)));
                try (InputStream src = file.newInputStream()) {
                    while (true) {
                        count = src.read(buffer);
                        if (count == -1) {
                            break;
                        }
                        tar.write(buffer, 0, count);
                    }
                }
            }
            tar.close();
        } catch (IOException | RuntimeException | Error e) {
            result.deleteFile();
            throw e;
        }
        return result;
    }

    public Map<String, BuildArgument> formals() throws IOException {
        return BuildArgument.scan(directory.join("Dockerfile"));
    }

    public String toString() {
        return directory.toString();
    }
}
