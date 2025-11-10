#!/bin/sh

exec java -jar satd-analyzer-jar-with-all-dependencies.jar -r repos.txt -d mySQL.properties -t debthunter -e "$@"

