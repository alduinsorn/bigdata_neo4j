# bigdata_neo4j

### Author:
- **Thibaut Michaud**
- **Romain Peretti**

This laboratory exercise aims at showing databases from a performance and operation point of view. It is again based on Neo4j, and will build on the knowledge acquired during the first laboratory.

## Description
This laboratory consists of loading in neo4j a relatively big graph: the DBLP article data.  DBLP  was  an  original  effort  led  by  University  Trier  in  Germany  to  create  a  map  of scientific contributions, and this way before scholar.google.com emerged as the leading tool to track papers and citations. 

## Some useful informations
**To build and run the docker container**:
```
./build.sh
docker-compose up
```

**Neo4j interface**: http://172.24.0.10:7474/browser/
the username is `neo4j` and the password is `test`.

## Approach used for loading the graph

We based our approach on the one proposed in Java.

### JSON formatting
First, we have to format the JSON file to be able to simplify the loading process. To do so, we have to replace all the `NumberInt(x)` by `x` with the following command:
```
sed -i 's/NumberInt(\([^)]*\))/\1/g' dblp.json
```

### Loading and browsing the JSON file

To load and browse the JSON file, we used the Jackson library. 
To connect to the Neo4j database, we used the Neo4j Java driver.

We browse the JSON array element by element and create a new thread for each article to add using the thread pool with a fixed number of threads which is the number of cores of the machine. It allows us to parse and insert the articles in parallel and to speed up the process.

### Creating the nodes and relationships

We first parse the article title and _id fields and create an ARTICLE node. Then, if the article has an authors field, we create AUTHOR nodes and AUTHORED relationships. Finally, if the article has a references field, we create CITES relationships.

For creating the article node we use the MERGE clause to avoid creating duplicate nodes. We also use the ON CREATE and ON MATCH clauses to set the title field only if the node is created.
```
MERGE (a:ARTICLE {_id: $articleId})
ON CREATE SET a.title = $title
ON MATCH SET a.title = $title
```

For creating the AUTHOR nodes and AUTHORED relationships, we use the UNWIND clause to iterate over the authors array to add all the authors with a single query.
```
UNWIND $authors AS author
CREATE (b:AUTHOR {_id: author._id, name: author.name})
WITH b
MATCH (a:ARTICLE) WHERE a._id = $articleId
CREATE (b)-[r:AUTHORED]->(a)
```

Finally, for creating the CITES relationships, we use the UNWIND clause to iterate over the references array to add all the references with a single query but this time we use the MERGE clause because the articles referenced may not be in the database.
```
UNWIND $references AS reference
MATCH (a:ARTICLE) WHERE a._id = $articleId
MERGE (b:ARTICLE {_id: reference})
CREATE (a)-[r:CITES]->(b)
```

## Performance tests
The performance tests were done on a machine with 8 cores and 8GB of RAM. The Neo4j database was running on the same machine and 4GB of RAM were allocated to it. The Neo4j database was empty before the tests.

The tests were done considering 100, 1000, 5000, 10000, 15000, 20000, 25000 articles. The results are shown in the table below.

| Nodes |  Run 1 |  Run 2 |  Run 3 | Average |
|-------|--------|--------|--------|---------|
|  100  |  4.663 |  4.670 |  4.196 |  4.539  |
|  1000 |  9.912 |  8.812 |  9.286 |  9.256  |
|  5000 | 26.030 | 25.498 | 26.027 | 25.967  |
| 10000 | 48.321 | 49.233 | 49.753 | 49.167  |
| 15000 | 92.451 | 86.972 | 77.924 | 85.465  |
| 20000 | 183.166| 190.717| 186.625| 186.475 |
| 25000 | 1066.650| 1102.230| 983.532| 1047.967|

{"number_of_articles": 100, "memoryMB": "4000", "seconds": 4.510}  
{"number_of_articles": 1000, "memoryMB": "4000", "seconds": 9.337}  
{"number_of_articles": 5000, "memoryMB": "4000", "seconds": 25.852}  
{"number_of_articles": 10000, "memoryMB": "4000", "seconds": 49.102}  
{"number_of_articles": 15000, "memoryMB": "4000", "seconds": 85.782}  
{"number_of_articles": 20000, "memoryMB": "4000", "seconds": 186.836}  
{"number_of_articles": 25000, "memoryMB": "4000", "seconds": 1050.804}  

