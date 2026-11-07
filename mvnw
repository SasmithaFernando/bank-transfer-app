#!/bin/sh
MVN_TARGET="C:/Users/SASMITHA/.m2/wrapper/dists/apache-maven-3.10.0/2aaba761688b1a6ea5a46e7e5f4d5406ed7a75ee8da95e5e3079863a23461c9b/bin/mvn"
if [ -f "$MVN_TARGET" ]; then
    "$MVN_TARGET" "$@"
else
    mvn "$@"
fi
