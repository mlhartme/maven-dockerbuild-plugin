# Maven Dockerbuild Plugin

This is Maven plugin to build Docker images. It'a not intendented to run images (e.g. for testing).

## Shared builds

The main features of this plugin is *shared Docker builds*. Shared builds means that the Dockerfile (or more precisely:
the Docker build context including the Dockerfile) is *not* part of the respective Maven module or projects, i.e. it's not part of the source
tree and it's not specified inline in the pom (like the fabric8 plugin allows). Instead, the Docker build it loaded from a central, shared location.

We hope to benefit form Shared Docker build because
* simplify maintenance: we just have to updates a small number shared builds instead of a possibly hug number of projects
* separation: Java developers can concentrate on their Java build - they don't have to care about the best way to build an image for them,
  they simply reference the latest shared build that fits their framework/setup. Otherwise, many developers will probably copy-and-paste
  a Dockerfile and not keep it up-to-date
* operations: is much easier to keep a small number of different shared builds up and running. Otherwise, you have to check the particular
  build of every individual application.

Rational: shared builds is the reason I wrote this plugin; I didn't find a proper way to do this in other Maven Docker plugins, and I want
to encourage Java developers *not* to copy-paste a Docker build into their project.


## Configuration

`dockerbuild` specifies the Dockerbuild to use

## Parametrization

Dockerfiles can be parameterized with arguments, i.e. using the `ARG` directive. You can set arguments in the plugin configuration inside
the `arguments` section, e.g.

    <configuration>
      ...
      <arguments>
        <memory>2048</memory>
      </arguments>
      ...
    </configuration>

passes 2048 to the `memory` Dockerfile argument.

In addition, the plugin automatically assigns various types of arguments. Argument types are distinguished by their name prefix.

### Artifact arguments

Uee artifact arguments to add Maven artifacts the Docker build context.

* `artifactWar` copies the war file into the build context and sets the argument to the respective path
* `artifactJar` copies the jar file into the build context and sets the argument to ths respective path


### Pom arguments

Use pom argument to pass pom data into you Dockerfile.

* `pomScm`  scm developerConnection or - if not set - connection


### Build argument

User build arguments to pass additional data into your Dockerfile

* `buildComment` as specified for the build
* `buildOrigin` current user and the machine running this build


## Implementation

This plugin is pretty simple:
* resolve artifact containing the docker build
* unpack into target/dockerfile
* copy artifact arguments into this directory
* user Dockers Java Client API to build the image;
  note that the plugin outputs the equicalent docker command line call to document precisely that it does - the command line is *not* used


## Links

Other Docker Maven Plugins I'm aware of:

* https://github.com/fabric8io/docker-maven-plugin
* https://github.com/spotify/docker-maven-plugin
* https://github.com/spotify/dockerfile-maven
