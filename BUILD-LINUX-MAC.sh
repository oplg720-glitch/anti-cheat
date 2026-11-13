#!/usr/bin/env sh
set -eu
java -version
gradle clean build
printf '\nBuilt: build/libs/AegisAC-Meteor-1.7.2.jar\n'
