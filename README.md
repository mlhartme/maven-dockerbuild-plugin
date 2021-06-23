# Maven Dockerbuild Plugin

This is a Maven plugin to build Docker images.

Goals: https://mlhartme.github.io/maven-dockerbuild-plugin/plugin-info.html

Note: It does not provide functionality to run images.

## Dockerbuilds

A dockerbuild is a Maven artifact (more precisely: a jar artifact) containing a Docker build context (i.e. Dockerfile and additional files as needed,
for example configuration files you want to add to the image). Dockerbuilds are managed centrally in your favorite Maven repository. The idea is to
have one dockerbuild for each setup or framework you use, e.g. a tomcat-war dockerbuild and a spring-boot dockerbuild.
To build an image for a Maven project (or a module in a multi-module build), you just select the appropriate dockerbuild from the repository.
The plugin resolves it (i.e. downloads the jar to the local repository - if necessary), unpacks it into a local directory, and builds the
by invoking Docker daemon with the appropriate build arguments.

## Setup

Prerequisite:
* Java 11 or newer to run the plugin
* Docker installed, accessible for the current user.
  That's the easiest way; technically, it's enough to just have the Docker daemon running and accessible for the current user.

Add this snippet to the **pluginsManagement** in your pom (or, if you have one, in your parent pom)

      <plugin>
        <groupId>net.oneandone.maven.plugins</groupId>
        <artifactId>dockerbuild</artifactId>
        <version>1.2.0</version>
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
      </plugin>

Next, add a snippet like this to the **plugins** in your pom

      <plugin>
        <groupId>net.oneandone.maven.plugins</groupId>
        <artifactId>dockerbuild</artifactId>
        <configuration>
          <library>com.ionos.maven.plugins.dockerbuild.library</library>
          <dockerbuild>your-docker-build</dockerbuild>
          <image>contargo.server.lan/cisoops-public/%a:%V</image>
          <arguments>
            <war>%copy:%artifact:war</war>
          </arguments>
        </configuration>

Adjust
* `library` to specify the groupId of your dockerbuilds in your repository
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

You can use various *directives* in argument values. A directive starts with a `%` followed by the directices name and arguments sepeated by
`:`. The following directives are available:

* `%artifact:`*extension* or `%artifact:`*classifier*`:`*extension* evaluates to the path of this artifact
* `%base64:`*string* evaluates to the base64 encoded *string*
* `%copy:`*file* copies the file (specified as a path; relative paths are relative to the projects basedir)
  into the Docker build context and evaluates to the path within the context
* `%file:`*file* evaluates to the file contents; relatives paths are relative to the projects basedir
* `%filter:`*string* evaluates to string with all Maven variables substituted

Example: an argument

  <war>%copy:%artifact:war</war>

copies that war artifact into the Dockerbuild context an sets the `war` argument to the path within the context.


## Image Placeholders

You can use the following place holders when specifying the image name:

* `%a` expands to the artifactId of the current artifact
* `%b` expands to the current branch when building a snapshot;
       otherwise (i.e. when building a release) expands to the empty string;
       (note: this is technically mandatory when using the Maven Release plugin.
       `mvn release:perform` creates a clone for a specific commit, there's no associated
       branch this plugin could detect)
* `%g` expands to the groupId of the current artifact
* `%V` expands to the version of the current artifact; if the version contains "-SNAPSHOT",
       that's replaced by a timestamp

In addtion you can use prefixed placeholders. If you specify - e.g. - `%-b` the expanded value is
prefixed with `-`, but only for none-empty values.

## Properties

The build goal defines the following properties within Maven; use them for property arguments of Maven file filtering

* dockerbuild.origin    current host and user
* dockerbuild.image     image tag

## Rationale

The main rationale behind dockerbuilds is to keep Dockerfiles separate from the Maven project you're using it for. This helps to:
* Simplify maintenance: we just have to update a small number dockerbuilds instead of a possibly huge number of Dockerfiles spread in Maven projects
* Separation: Java developers can concentrate on their Java build - they don't have to care about the latest best practice to build an
  image for them; instead, they simply pick the latest dockerbuild that fits their framework/setup.
* Avoid copy/paste: nobody's forced to google for suitable Dockerfiles - that could get copied to the project source - and easily become
  unmaintained.
* Operations: is much easier to keep a small number of dockerbuilds in good shape (in particular: updated with security fixes).

The price you have to pay: you cannot quickly adjust a Dockerfile for your particular Maven project - you have to adjust a shared version
in your dockerbuild library.

Dockerbuilds are the reason I wrote this plugin; I didn't find a proper way to do this with other Maven Docker plugins. A common
approach to get close is to provide central base images and use a simple Dockerfile in every Maven project to glue things together.


## Implementation

This plugin is pretty simple. The `build` goal prints equivalent shell commands to indicate what it does:
* resolve artifact containing the Dockerfile
* unpack into `target/dockerbuild/context`
* evaluate arguments (and possibly copying files into the context if a %copy directive is encountered)
* use Docker's Java Client API to build the image;

Note that the plugin does not actually use the shell command it prints to the console -- this is just to document what it does and to simplify
manual testing - you can copy-and-paste the commands to retry your build.

Build notes:

* Update plugin documentation:

      mvn clean verify site site:stage scm-publish:publish-scm -Dscmpublish.content=target/staging/dockerbuild

  It takes a few minutes for gh pages to sync.


## Links

Other Docker Maven Plugins I'm aware of:

* https://github.com/fabric8io/docker-maven-plugin
* https://github.com/spotify/docker-maven-plugin
* https://github.com/spotify/dockerfile-maven
