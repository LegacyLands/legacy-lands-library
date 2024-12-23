package me.qwqdev.library.mongodb.factory;

import com.mongodb.MongoClientSettings;
import lombok.experimental.UtilityClass;
import me.qwqdev.library.mongodb.model.MongoDBConnectionConfig;
import org.bson.UuidRepresentation;

/**
 * MongoDBConnectionConfigFactory is a factory class responsible for creating instances of {@link MongoDBConnectionConfig}.
 *
 * @author qwq-dev
 * @see MongoDBConnectionConfig
 * @since 2024-12-20 12:18
 */
@UtilityClass
public final class MongoDBConnectionConfigFactory {
    /**
     * Creates a default {@link MongoDBConnectionConfig} instance using the standard UUID representation.
     *
     * @param databaseName the name of the MongoDB database to connect to
     * @param mongoURL     the MongoDB connection URL, e.g., "mongodb://localhost:27017"
     * @return a configured {@link MongoDBConnectionConfig} instance
     * @throws IllegalArgumentException if either the database name or MongoDB URL is null or empty
     * @see MongoDBConnectionConfig
     */
    public static MongoDBConnectionConfig create(String databaseName, String mongoURL) {
        return new MongoDBConnectionConfig(databaseName, mongoURL);
    }

    /**
     * Creates a {@link MongoDBConnectionConfig} instance with a custom UUID representation.
     *
     * @param databaseName       the name of the MongoDB database to connect to
     * @param mongoURL           the MongoDB connection URL, e.g., "mongodb://localhost:27017"
     * @param uuidRepresentation the UUID representation to be used in MongoDB documents
     * @return a configured {@link MongoDBConnectionConfig} instance
     * @throws IllegalArgumentException if either the database name or MongoDB URL is null or empty
     * @see MongoDBConnectionConfig
     * @see UuidRepresentation
     */
    public static MongoDBConnectionConfig create(String databaseName, String mongoURL, UuidRepresentation uuidRepresentation) {
        return new MongoDBConnectionConfig(databaseName, mongoURL, uuidRepresentation);
    }

    /**
     * Creates a {@link MongoDBConnectionConfig} instance using custom {@link MongoClientSettings}.
     *
     * @param mongoClientSettings the custom {@link MongoClientSettings} to configure the MongoClient
     * @return a configured {@link MongoDBConnectionConfig} instance
     * @throws IllegalArgumentException if {@link MongoClientSettings} is null
     * @see MongoDBConnectionConfig
     * @see MongoClientSettings
     */
    public static MongoDBConnectionConfig create(MongoClientSettings mongoClientSettings) {
        return new MongoDBConnectionConfig(mongoClientSettings);
    }
}
