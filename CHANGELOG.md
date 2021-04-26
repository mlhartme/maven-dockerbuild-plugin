# Changelog

## 1.2.0 (pending)

* renamed `master` branch to `main`
* stop pulling arguments, that's like using global variables - at first, it's handy, but as things grow, it becomes scary magic
  * added %base64 directive to base64-encode a string
  * added %file directive to read file into an argument  
  * dumped `property` and `pom` arguments - use explicit argument that reference the property instead
  * dumped `file` arguments - use `base64` and `file` directives instead
* skippable builds
  * build goal:
    added special dockerbuild "skip" that simply disables the plugin; this way you can configure the plugin with a simple property 
    instead of a profile (with also simplifies testing, because everybody can invoke the plugin directly with -Ddockerbuild=foo)
  * push goal:
    if target/image file does not exists (e.g. because build was skipped): issue a note and do nothing
  

## 1.1.1 (2021-02-26)

* file arguments now also remove '-' and  '_' before matching names


## 1.1.0 (2021-02-23)

* added %b placeholder to include git branch name to image
* dumped comment, you can use arbitrary system properties instead
* added file arguments
* added property arguments
* `build` now defines Maven properties
  * dockerbuild.image
  * dockerbuild.origin  
* dumped `build` arguments, use `property` arguments with one of the properties above


## 1.0.1 (2020-12-02)

Added artifactName parameter, which is used instead of finalName for implicit artifact arguments.
This is partially a work-around for problems mit build-helper-plugin, it's parsed version properties
are not resolved in project.getBuild().getFinalName().


## 1.0.0 (2020-11-30)

Initial version. This is a roughtly a spin-off of the "stool build" command from 
Stool 6 https://github.com/mlhartme/stool, but switch from stool 
docker client to https://github.com/docker-java/docker-java
