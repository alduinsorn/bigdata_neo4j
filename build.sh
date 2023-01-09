#  #!/usr/bin/sh

sudo rm -rf neo4j_mount/data
sudo mkdir neo4j_mount/data
docker build . -t neo4jtp
