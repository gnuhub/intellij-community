#!/usr/bin/env bash
set -x
basedir=$(pwd)
java -Xmx612m -XX:MaxPermSize=152m \
-DjdkHome='/System/Library/Java/JavaVirtualMachines/1.6.0.jdk/Contents/Home' \
-Djava.awt.headless=true \
-Dhome="${basedir}" \
-Dout="${basedir}/out" \
-Dtmp.dir="${basedir}/out/tmp" \
-Dgant.script="${basedir}/build/scripts/dist.gant" \
-Dteamcity.build.tempDir="${basedir}/out/tmp" \
-Didea.test.group=ALL_EXCLUDE_DEFINED \
-jar "${basedir}/lib/ant/lib/ant-launcher.jar" \
-f "${basedir}/build/gant.xml"