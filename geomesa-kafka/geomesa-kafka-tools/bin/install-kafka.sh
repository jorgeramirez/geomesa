#!/usr/bin/env bash
#
# Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
# All rights reserved. This program and the accompanying materials
# are made available under the terms of the Apache License, Version 2.0 which
# accompanies this distribution and is available at
# http://www.opensource.org/licenses/apache2.0.php.
#

maven_server="https://search.maven.org/remotecontent?filepath="

zookeeper_version="%%zookeeper.version.recommended%%"

# kafka 9 versions
kafka_version="%%kafka.version%%"
zkclient_version="%%zkclient.version%%"
jopt_version="%%jopt.version%%"

# kafka 10 versions
# kafka_version="0.10.2.1"
# zkclient_version="0.10"
# jopt_version="4.9"

declare -a urls=(
  "${maven_server}org/apache/kafka/kafka_2.11/$kafka_version/kafka_2.11-$kafka_version.jar"
  "${maven_server}org/apache/kafka/kafka-clients/$kafka_version/kafka-clients-$kafka_version.jar"
  "${maven_server}org/apache/zookeeper/zookeeper/$zookeeper_version/zookeeper-$zookeeper_version.jar"
  "${maven_server}com/101tec/zkclient/$zkclient_version/zkclient-$zkclient_version.jar"
  "${maven_server}net/sf/jopt-simple/jopt-simple/$jopt_version/jopt-simple-$jopt_version.jar"
  "${maven_server}com/yammer/metrics/metrics-core/2.2.0/metrics-core-2.2.0.jar"
)

if [[ (-z "$1") ]]; then
  echo "Error: Provide one arg which is the target directory (e.g. /opt/jboss/standalone/deployments/geoserver.war/WEB-INF/lib)"
  exit
fi

install_dir=$1
NL=$'\n'
read -r -p "Install Kafka ${kafka_version} DataStore dependencies to '${install_dir}' (y/n)? " confirm
confirm=${confirm,,} #lowercasing
if [[ $confirm =~ ^(yes|y) || $confirm == "" ]]; then
  # get stuff
  for x in "${urls[@]}"; do
    fname=$(basename "$x");
    echo "fetching ${x}";
    wget -O "${1}/${fname}" "$x" || { rm -f "${1}/${fname}"; echo "Failed to download: ${x}"; \
      errorList="${errorList[@]} ${x} ${NL}"; };
  done

  if [[ -n "${errorList}" ]]; then
    echo "Failed to download: ${NL} ${errorList[@]}";
  fi
else
  echo "Installation cancelled"
fi
