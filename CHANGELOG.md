# Changelog

## 1.1.0 (pending)

* added property arguments.


## 1.0.1 (2020-12-02)

Added artifactName parameter, which is used instead of finalName for implicit artifact arguments.
This is partially a work-around for problems mit build-helper-plugin, it's parsed version properties
are not resolved in project.getBuild().getFinalName().


## 1.0.0 (2020-11-30)

Initial version. This is a roughtly a spin-off of the "stool build" command from 
Stool 6 https://github.com/mlhartme/stool, but switch from stool 
docker client to https://github.com/docker-java/docker-java
