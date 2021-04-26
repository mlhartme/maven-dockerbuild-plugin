# Maven Dockerbuild Plugin

This is a Maven plugin to build Docker images.

Goals: https://mlhartme.github.io/maven-dockerbuild-plugin/plugin-info.html

Note: It does not provide functionality to run images.

## Dockerbuilds

A dockerbuild is a Maven artifact (more precisely: a jar artifact) containing a Docker build context, i.e. Dockerfile and additional files as needed
(for example configuration files you want to add to the image). Dockerbuilds are managed centrally in your favorite Maven repository. The idea is to
have one dockerbuild for each setup or framework you use in your repository, e.g. a tomcat-war Dockerbuild and a Spring boot dockerbuild.
To build an image for a Maven project (or a module in a multi-module build), you just select the appropriate dockerbuild from the repository.
The plugin resolves it (i.e. downloads the jar to the local repository - if necessary), unpacks it into a local directory, and invokes the
Docker daemon with the appropriate arguments to build the image.

## Setup

Prerequisite:
* Java 11 or newer to run the plugin
* Docker installed, accessible for the current user.
  That's the easiest way. Technically, it's enough to just have the Docker daemon running and accessible for the current user.

You'll typically add a snippet like this

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
          <dockerbuild>tomcat-war</dockerbuild>
          <image>contargo.server.lan/cisoops-public/%a:%V</image>
          <arguments>
            <war>%copy:%artifact:war</war>
          </arguments>
        </configuration>
      </plugin>

to your pom. Adjust
* `library` to specify the groupId of your dockerbuilds
* `dockerbuild` to specify the artifactId of the dockerbuild to use
* `image` to start with your Docker registry and to match your naming conventions

## Arguments

Dockerfiles can be parameterized using Docker's `ARG` instruction. You can set these arguments in the plugin configuration inside
the `arguments` element; e.g.

    <configuration>
      ...
      <arguments>
        <memory>2048</memory>
      </arguments>
      ...
    </configuration>

passes 2048 to the `memory` Dockerfile argument.

You can use various directives in argument values:

* %artifact:<extension> evaluates to the path of this artifact
* %copy:<path> copies the path into the context and evalues to the path within the context
* %base64:<string> evalues to the base64 encoded string
* %file:<pathToFile> evalues to the file contents

## Properties

The build goal defines the following properties within Maven; use them for property arguments of Maven file filtering

* dockerbuild.origin    current host and user
* dockerbuild.image     image tag

## Rationale

The main rationale behind dockerbuilds is to keep Dockerfiles separate from the Maven project your using it for. This helps to:
* Simplify maintenance: we just have to update a small number dockerbuilds instead of a possibly hug number of Dockerfile spread in Maven projects
* Separation: Java developers can concentrate on their Java build - they don't have to care about the latest best practice to build an
  image for them; instead, they simply pick the latest dockerbuild that fits their framework/setup.
* Avoid copy/paste: nobody's forced to Google for suitable Dockerfiles - that could get copied to the project source - and easily become
  unmaintained.
* Operations: is much easier to keep a small number of dockerbuild in good shape (in particular: updated with security fixes).

The price you have to pay: you cannot quickly adjust a Dockerfile for your particular Maven project - you have to adjust a shared version
in your Dockerfile library.

Dockerbuilds are the reason I wrote this plugin; I didn't find a proper way to do this with other Maven Docker plugins. A common
approach to get close is to provide central base images and use a simple Dockerfile in every Maven project to glue things together.


## Implementation

This plugin is pretty simple. The build goal prints equivalent shell commands to log what it does:
* resolve artifact containing the Dockerfile
* unpack into `target/dockerbuild/context`
* copy artifact arguments into this directory
* use Docker's Java Client API to build the image;

Note that the plugin does not actually use the shell command it prints to the console -- this is just to document what it does and to simplify
manual testing - you can copy-and-paste the commands to retry your build.


## Links

Other Docker Maven Plugins I'm aware of:

* https://github.com/fabric8io/docker-maven-plugin
* https://github.com/spotify/docker-maven-plugin
* https://github.com/spotify/dockerfile-maven
