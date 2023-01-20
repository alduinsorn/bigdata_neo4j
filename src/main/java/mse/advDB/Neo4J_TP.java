package mse.advDB;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Values;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.neo4j.driver.Record;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class Neo4J_TP {
    public static void main(String[] args) throws IOException, InterruptedException {
        String jsonPath = System.getenv("JSON_FILE");
        System.out.println("Path to JSON file is " + jsonPath);
        int nbArticles = Integer.max(10, Integer.parseInt(System.getenv("MAX_NODES")));
        System.out.println("Number of articles to consider is " + nbArticles);
        String neo4jIP = System.getenv("NEO4J_IP");
        System.out.println("IP addresss of neo4j server is " + neo4jIP);
        int nbThreads = Runtime.getRuntime().availableProcessors();
        System.out.println("Number of threads to use is " + nbThreads);

        //Execute this command sed -i 's/NumberInt(\([^)]*\))/\1/g' dblp.json to remove the NumberInt() from the json file
        System.out.println("Removing NumberInt() from the json file ...");
        String sedCommand = "sed -i 's/NumberInt(\\([^)]*\\))/\\1/g' " + jsonPath;
        Process process = Runtime.getRuntime().exec(sedCommand);
        process.waitFor();

        Driver driver = GraphDatabase.driver("bolt://" + neo4jIP + ":7687", AuthTokens.basic("neo4j", "test"));
        boolean connected = false;
        do {
            try {
                System.out.println("Sleeping a bit waiting for the db");
                Thread.yield();
                Thread.sleep(5000); // let some time for the neo4j container to be up and running
                driver.verifyConnectivity();
                connected = true;
            } catch (Exception e) {
            }
        } while (!connected);
        System.out.println("Connected to the db");

        // Create a thread pool for parsing and inserting data concurrently
        ExecutorService executor = Executors.newFixedThreadPool(nbThreads);

        // Starting a timer
        long startTime = System.currentTimeMillis();

        // Create a JsonFactory and a JsonParser
        JsonFactory factory = new JsonFactory();
        JsonParser parser = factory.createParser(new File(jsonPath));

        // Set the parser to use the "lenient" mode, which allows it to parse
        // non-standard JSON syntax more leniently
        parser.enable(JsonParser.Feature.ALLOW_COMMENTS);
        parser.enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES);
        parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

        // Create an ObjectMapper to use for reading JSON nodes
        ObjectMapper mapper = new ObjectMapper();

        // Get array of articles
        parser.nextToken();

        // Iterate over the elements in the JSON array
        int parsedArticles = 0;

        AtomicInteger counter = new AtomicInteger(0);

        while (parser.nextToken() != null && parsedArticles < nbArticles) {
            // Read the next element in the array as a JSON node
            JsonNode json = mapper.readTree(parser);

            // // Print the progress every 1000 articles in percentage
            // if (parsedArticles % 999 == 0) {
            //     System.out.println(String.format("%.2f", parsedArticles * 100.0 / nbArticles) + "%");
            // }

            // Create a new thread for parsing and inserting the current article
            executor.submit(() -> {
                try (Session session = driver.session()) {
                    // Parse the current article and insert it into the database
                    parseAndInsertArticle(session, json);
                } finally {
                    counter.decrementAndGet();
                }
            });

            counter.incrementAndGet();

            parsedArticles++;
        }

        System.out.println("All threads submitted.");
        System.out.println("Parsing and adding the first " + nbArticles + " articles to the database...");

        // Wait for all threads to finish (await termination)
        executor.shutdown();
        while (!executor.isTerminated()) {
            // Print percentage of completion
            System.out.println(String.format("%.2f", (nbArticles - counter.get()) * 100.0 / nbArticles) + "%");
            Thread.sleep(500);
        }

        // Stop the timer
        long endTime = System.currentTimeMillis();

        // Print the elapsed time in seconds (with 3 decimals)
        System.out.println("Elapsed time: " + String.format("%.3f", (endTime - startTime) / 1000.0) + "s");

        // Close the parser
        parser.close();

        // Close the session
        driver.close();
    }

    private static void parseAndInsertArticle(Session session, JsonNode json) {
        // Extract the "_id" field and create an ARTICLE node
        String articleId = json.get("_id").asText();
        // System.out.println("Article N°" + parsedArticles + " : " + articleId);

        String title = json.get("title").asText();
        session.run("MERGE (a:ARTICLE {_id: $articleId}) " +
                "ON CREATE SET a.title = $title " +
                "ON MATCH SET a.title = $title",
                Values.parameters("articleId", articleId, "title", title));

        // If the object has an "authors" field, create AUTHOR nodes and AUTHORED
        // relationships
        if (json.has("authors")) {
            JsonNode JSON_authors = json.get("authors");
            List<Map<String, Object>> authors = new ArrayList<>();
            for (JsonNode author : JSON_authors) {
                Map<String, Object> authorMap = new HashMap<>();
                // Skip authors without an "_id" field
                if (author.has("_id")) {
                    authorMap.put("_id", author.get("_id").asText());
                    if (author.has("name")) {
                        authorMap.put("name", author.get("name").asText());
                    } else {
                        authorMap.put("name", "Unknown");
                    }
                    authors.add(authorMap);
                }
            }

            Result res = session.run("UNWIND $authors AS author " +
                    "CREATE (b:AUTHOR {_id: author._id, name: author.name}) " +
                    "WITH b " +
                    "MATCH (a:ARTICLE) WHERE a._id = $articleId " +
                    "CREATE (b)-[r:AUTHORED]->(a)", Values.parameters("authors", authors, "articleId", articleId));
            // System.out.println("Number of AUTHOR nodes created: " +
            // res.consume().counters().nodesCreated());
        }

        // If the object has a "references" field, create CITES relationships
        if (json.has("references")) {
            List<String> references = new ArrayList<>();
            for (JsonNode reference : json.get("references")) {
                references.add(reference.asText());
            }

            Result result = session.run("UNWIND $references AS reference " +
                    "MATCH (a:ARTICLE) WHERE a._id = $articleId " +
                    "MERGE (b:ARTICLE {_id: reference}) " +
                    "CREATE (a)-[r:CITES]->(b)",
                    Values.parameters("references", references, "articleId", articleId));
            // System.out.println("Number of CITES relationships created: " +
            // result.consume().counters().relationshipsCreated());
        }
    }
}
