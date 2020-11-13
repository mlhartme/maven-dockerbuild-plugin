# Maven Dockerbuild Plugin

This is a Maven plugin to build Docker images. (It does not provide run functionality.)

Goals: https://mlhartme.github.io/maven-dockerbuild-plugin/plugin-info.html


## Shared builds

The main feature of this plugin is *shared Docker builds*. Shared builds means that the Dockerfile (or more precisely:
the Docker build context including the Dockerfile) is *not* part of the respective Maven module: it's not part of the source
tree and it's not specified inline in the pom (like the fabric8 plugin allows). Instead, the Docker build is defined in an artifact; the plugin resolves this artifact, unpacks it, and uses it to build the image.

Shared Docker builds help with:
* Simplify maintenance: we just have to update a small number of shared builds instead of a possibly huge number of projects
* Separation: Java developers can concentrate on their Java build - they don't have to care about the best way to build an image for them, they simply reference the latest shared build that fits their framework/setup. Otherwise, many developers will probably copy-and-paste a Dockerfile and not keep it up-to-date
* Operations: it is much easier to keep a small number of different shared builds up and running. Otherwise, you'd have to check the particular build of every individual application.

Rational: shared builds is the reason I wrote this plugin; I didn't find a proper way to do this in other Maven Docker plugins (a common
approach to get close to this: provide shared base images and keep the Dockerfile in maven as small as possible). And I want
to encourage Java developers *not* to copy-paste a Docker build into their project.


## Configuration

Here's an example:

      <plugin>
        <groupId>net.oneandone.maven.plugins</groupId>
        <artifactId>dockerbuild</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <executions>
          <execution>
            <id>dockerbuild-package</id>
            <goals>
              <goal>build</goal>
            </goals>
          </execution>
          <execution>
            <id>dockerbuild-deploy</id>
            <goals>
              <goal>push</goal>
            </goals>
          </execution>
        </executions>
        <configuration>
          <library>com.ionos.maven.plugins.dockerbuild.library</library>
          <dockerbuild>vanilla-war</dockerbuild>
          <image>contargo.server.lan/cisoops-public/%a:%V</image>
          <arguments>
            <memory>2048</memory>
          </arguments>
        </configuration>
      </plugin>


## Parameterization

Dockerfiles can be parameterized using the `ARG` instruction. You can set these arguments in the plugin configuration inside
the `arguments` element; e.g.

    <configuration>
      ...
      <arguments>
        <memory>2048</memory>
      </arguments>
      ...
    </configuration>

passes 2048 to the `memory` Dockerfile argument.

In addition, the plugin automatically assigns various types of arguments, depending on their name prefix. E.g. pom arguments are arguments
starting with `pom`.

### Artifact arguments

Use artifact arguments to add Maven artifacts to the Docker build context.

* `artifactWar` copies the war file into the build context and sets the argument to the respective path
* `artifactJar` copies the jar file into the build context and sets the argument to the respective path


### Pom arguments

Use pom arguments to pass pom data into you Dockerfile.

* `pomScm`  scm developerConnection or - if not set - connection


### Build arguments

Use build arguments to pass additional data into your Dockerfile.

* `buildComment` as specified for the build
* `buildOrigin` current user and the machine running this build


## Implementation

This plugin is pretty simple. The build plugin prints equivalent build commands to log what it does:
* resolve artifact containing the docker build
* unpack into target/dockerfile
* copy artifact arguments into this directory
* use Dockers Java Client API to build the image;
  note that the plugin outputs the equivalent docker command line call to document precisely what it does - the command line is *not* used


## Links

Other Docker Maven Plugins I'm aware of:

* https://github.com/fabric8io/docker-maven-plugin
* https://github.com/spotify/docker-maven-plugin
* https://github.com/spotify/dockerfile-maven
