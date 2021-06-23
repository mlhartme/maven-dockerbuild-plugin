# Changelog

## 1.2.2 (pending)

* changed `%b` placeholder to expand to empty strings when building a release
* added prefixed placeholders: if you specify - e.g. - `%-b` the expanded value is 
  prefixed with `-` unless it's empty.

## 1.2.1 (2021-05-11)

* added `latest` parameter to automatically create a local latest tag; not that this tag will never be deployed by the plugin,
  and that the `image` file still holds the specified normal image tag. Default value is true.
* automatically skip dockerbuild goals if packaging is not war. This is useful to attached the dockerbuild in aggregating poms.


## 1.2.0 (2021-04-28)

* stop pulling arguments, that's like using global variables - at first, it's handy, but as things grow, it becomes scary magic
  * added %base64 directive to base64-encode a string
  * added %file directive to read file into an argument  
  * added %artifact directory to reference artifacts and also copy them to the context  
  * dumped `property` and `pom` arguments - use explicit argument that reference the property instead
  * dumped `file` arguments - use `base64` and `file` directives instead
  * dumped `artifact` arguments - use `artifact` directive instead
* added skip argument to all goals; this is useful - e.g. - to readily configure your dockerbuild but skip it by default, 
  and only enable when needed with `-Ddockerbuild.skip=false`
* renamed branch `master` to `main`
  

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
