#!/bin/bash
sed 's/NumberInt(\([^)]*\))/\1/g' file_to_format.json > file.json
mvn exec:java
