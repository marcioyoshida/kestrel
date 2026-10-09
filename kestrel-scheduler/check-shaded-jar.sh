#!/usr/bin/env bash
# The shaded jar goes on Rundeck's classpath next to plugins with their own AWS SDK. It must hold
# nothing outside org/rundeck/kestrel/ (an unrelocated dependency would clash, as the S3 log
# plugin did with "UrlConnectionSdkHttpService not a subtype").
set -euo pipefail
jar=$(ls "$(dirname "$0")"/build/libs/*-shaded.jar)
leaked=$(unzip -Z1 "$jar" | grep '\.class$' | grep -v '^org/rundeck/kestrel/' | cut -d/ -f1-3 | sort -u || true)
if [ -n "$leaked" ]; then
  echo "Unrelocated classes in $jar:"; echo "$leaked"; exit 1
fi
echo "shaded jar ok: $(unzip -Z1 "$jar" | grep -c '\.class$') classes, all under org/rundeck/kestrel/"
