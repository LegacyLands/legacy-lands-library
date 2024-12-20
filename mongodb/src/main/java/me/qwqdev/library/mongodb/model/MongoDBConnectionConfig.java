package me.qwqdev.library.mongodb.model;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.morphia.Datastore;
import dev.morphia.Morphia;
import lombok.Data;
import org.bson.UuidRepresentation;

/**
 * MongoDBConnectionConfig is a configuration class responsible for setting up a
 * MongoDB connection and initializing the Morphia datastore for database interactions.
 *
 * @author qwq-dev
 * @since 2024-12-20 10:12
 */
@Data
public class MongoDBConnectionConfig {
    private final MongoClient mongoClient;
    private final Datastore datastore;

    /**
     * Constructs a new MongoDBConnectionConfig using the specified database name
     * and MongoDB connection URL. The default UUID representation (STANDARD) will be used.
     *
     *
     * @param databaseName the name of the MongoDB database to connect to
     * @param mongoURL     the MongoDB connection URL, such as "mongodb://localhost:27017"
     * @throws IllegalArgumentException if either the database name or MongoDB URL is null or empty
     */
    public MongoDBConnectionConfig(String databaseName, String mongoURL) {
        this(databaseName, mongoURL, UuidRepresentation.STANDARD);
    }

    /**
     * Constructs a new MongoDBConnectionConfig using the specified database name,
     * MongoDB connection URL, and UUID representation. This constructor allows customization
     * of UUID handling in MongoDB documents.
     *
     * @param databaseName       the name of the MongoDB database to connect to
     * @param mongoURL           the MongoDB connection URL, such as "mongodb://localhost:27017"
     * @param uuidRepresentation the UUID representation to be used in MongoDB documents
     * @throws IllegalArgumentException if either the database name or MongoDB URL is null or empty
     */
    public MongoDBConnectionConfig(String databaseName, String mongoURL, UuidRepresentation uuidRepresentation) {
        if (databaseName == null || databaseName.isEmpty()) {
            throw new IllegalArgumentException("Database name must not be null or empty.");
        }

        if (mongoURL == null || mongoURL.isEmpty()) {
            throw new IllegalArgumentException("MongoDB URL must not be null or empty.");
        }

        this.mongoClient = MongoClients.create(MongoClientSettings.builder()
                .uuidRepresentation(uuidRepresentation)
                .applyConnectionString(new ConnectionString(mongoURL))
                .build());
        this.datastore = Morphia.createDatastore(mongoClient, databaseName);
    }

    /**
     * Constructs a new MongoDBConnectionConfig using custom MongoClientSettings.
     *
     * @param mongoClientSettings the custom MongoClientSettings to configure the MongoClient
     * @throws IllegalArgumentException if the provided MongoClientSettings or application name is null
     */
    public MongoDBConnectionConfig(MongoClientSettings mongoClientSettings) {
        if (mongoClientSettings == null) {
            throw new IllegalArgumentException("MongoClientSettings must not be null.");
        }

        String applicationName = mongoClientSettings.getApplicationName();

        if (applicationName == null) {
            throw new IllegalArgumentException("Application name must not be null.");
        }

        this.mongoClient = MongoClients.create(mongoClientSettings);
        this.datastore = Morphia.createDatastore(mongoClient, applicationName);
    }

    /**
     * Closes the MongoDB client connection, releasing any resources held by the client.
     */
    public void close() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }
}
