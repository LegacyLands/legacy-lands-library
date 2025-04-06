package net.legacy.library.player.index;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import dev.morphia.Datastore;
import io.fairyproject.log.Log;
import net.legacy.library.mongodb.model.MongoDBConnectionConfig;
import net.legacy.library.player.model.LegacyEntityData;
import net.legacy.library.player.model.LegacyPlayerData;
import org.apache.commons.lang3.Validate;

/**
 * Utility class to manage MongoDB indexes for {@link LegacyPlayerData} and {@link LegacyEntityData}.
 *
 * <p>This class uses the project's configuration and logging utilities.
 * It ensures that necessary indexes are created in the target MongoDB database,
 * typically invoked during application startup to guarantee index existence.
 * It uses the provided {@link MongoDBConnectionConfig} to access the database.
 *
 * <p>The underlying MongoDB {@code createIndex} operation is idempotent.
 * This means calling the {@code ensure...} methods in this class repeatedly (e.g., on every application startup)
 * with the same parameters is safe and has the same effect as calling them once.
 * If an index already exists with the exact same specification, MongoDB will not rebuild it.
 * This ensures the necessary indexes are present without causing errors or performance issues on restarts.
 *
 * @author qwq-dev
 * @since 2025-4-6 14:12
 */
public class LegacyIndexManager {
    private static final String ENTITY_COLLECTION_NAME = "legacy-entity-data";
    private static final String PLAYER_DATA_COLLECTION_NAME = "legacy-player-data";
    private final MongoDatabase mongoDatabase;

    /**
     * Constructs an index manager using the MongoDB connection configuration.
     *
     * @param connectionConfig the {@link MongoDBConnectionConfig} object which provides access
     *                         to the {@link Datastore} and ultimately the {@link MongoDatabase}.
     * @throws IllegalStateException if the {@link MongoDatabase} cannot be retrieved from the config's {@link Datastore}.
     */
    public LegacyIndexManager(MongoDBConnectionConfig connectionConfig) {
        this.mongoDatabase = connectionConfig.getDatastore().getDatabase();
    }

    /**
     * Creates an instance of {@link MongoDBConnectionConfig}.
     *
     * @param connectionConfig the {@link MongoDBConnectionConfig} object which provides access
     *                         to the {@link Datastore} and ultimately the {@link MongoDatabase}.
     * @return a {@link MongoDBConnectionConfig} instance
     * @throws IllegalStateException if the {@link MongoDatabase} cannot be retrieved from the config's {@link Datastore}.
     */
    public static LegacyIndexManager of(MongoDBConnectionConfig connectionConfig) {
        return new LegacyIndexManager(connectionConfig);
    }

    /**
     * Ensures an index exists on the {@code entityType} field for the {@code legacy-entity-data} collection.
     *
     * <p>This index is useful for efficiently querying {@code LegacyEntityData} based on its type.
     */
    public void ensureEntityTypeIndex() {
        ensureIndex(ENTITY_COLLECTION_NAME, "entityType", Indexes.ascending("entityType"), null);
    }

    /**
     * Ensures an index exists on a specific key within the {@code attributes} map
     * for the {@code legacy-entity-data} collection.
     *
     * @param attributeKey the key within the attributes map to index (e.g., status, lastLogin).
     * @param sparse       if {@code true}, creates a sparse index (only indexes documents containing the key);
     *                     recommended for optional attributes.
     */
    public void ensureAttributeIndex(String attributeKey, boolean sparse) {
        Validate.notEmpty(attributeKey, "Attribute key cannot be empty.");

        String indexField = "attributes." + attributeKey;
        String indexName = "idx_attributes_" + attributeKey.toLowerCase().replace('.', '_');
        IndexOptions options = new IndexOptions().name(indexName).sparse(sparse);
        ensureIndex(ENTITY_COLLECTION_NAME, indexField, Indexes.ascending(indexField), options);
    }

    /**
     * Ensures a multikey index exists on a specific relationship type within the {@code relationships} map
     * for the {@code legacy-entity-data} collection.
     *
     * <p>This index is useful for efficiently querying entities based on their relationships
     * (e.g., finding all entities with a member relationship pointing to a specific target).
     *
     * @param relationshipType the relationship type key within the relationships map (e.g., member, owner).
     */
    public void ensureRelationshipIndex(String relationshipType) {
        Validate.notEmpty(relationshipType, "Relationship type cannot be empty.");

        String indexField = "relationships." + relationshipType;
        String indexName = "idx_relationships_" + relationshipType.toLowerCase().replace('.', '_');
        IndexOptions options = new IndexOptions().name(indexName);
        ensureIndex(ENTITY_COLLECTION_NAME, indexField, Indexes.ascending(indexField), options);
    }

    /**
     * Ensures an index exists on a specific key within the {@code data} map
     * for the {@code legacy-player-data} collection.
     *
     * @param dataKey the key within the data map to index (e.g., points, lastSeen).
     * @param sparse  if {@code true}, creates a sparse index; recommended for optional data fields.
     */
    public void ensurePlayerDataIndex(String dataKey, boolean sparse) {
        Validate.notEmpty(dataKey, "Data key cannot be empty.");

        String indexField = "data." + dataKey;
        String indexName = "idx_playerdata_" + dataKey.toLowerCase().replace('.', '_');
        IndexOptions options = new IndexOptions().name(indexName).sparse(sparse);
        ensureIndex(PLAYER_DATA_COLLECTION_NAME, indexField, Indexes.ascending(indexField), options);
    }

    /**
     * Helper method to create a MongoDB index if it doesn't already exist.
     *
     * <p>It generates a standardized index name and handles potential {@link MongoCommandException}s,
     * such as conflicts or the index already existing, using {@link Log} for reporting.
     *
     * @param collectionName the name of the target MongoDB collection.
     * @param indexKeyName   a descriptive name of the field being indexed (used for logging and potentially deriving the index name).
     * @param indexKeys      the BSON document specifying the index keys (e.g., using {@link Indexes#ascending(String...)}).
     * @param options        the {@link IndexOptions} (e.g., specifying name, sparsity); can be {@code null}.
     */
    private void ensureIndex(String collectionName, String indexKeyName, org.bson.conversions.Bson indexKeys, IndexOptions options) {
        String effectiveIndexName = (options != null && options.getName() != null) ? options.getName() : ("idx_" + indexKeyName.toLowerCase().replace('.', '_'));

        if (options == null) {
            options = new IndexOptions();
        }

        options.name(effectiveIndexName);

        try {
            mongoDatabase.getCollection(collectionName).createIndex(indexKeys, options);
        } catch (MongoCommandException exception) {
            int code = exception.getCode();
            String message = exception.getMessage();

            boolean isConflict = exception.hasErrorLabel("IndexOptionsConflict") || code == 85 ||
                    exception.hasErrorLabel("IndexKeySpecsConflict") || code == 86;
            boolean indicatesExists = message != null && message.toLowerCase().contains("already exists");

            if (isConflict) {
                Log.warn("Index creation conflict for field '%s' on collection '%s'. Name: '%s'. Check index definitions. Error: %s",
                        indexKeyName, collectionName, effectiveIndexName, message);
            } else if (!indicatesExists) {
                Log.error("MongoDB command failed while ensuring index '%s' on field '%s' for collection '%s'. Error Code: %d, Message: %s",
                        effectiveIndexName, indexKeyName, collectionName, code, message);
            }
        } catch (Exception exception) {
            Log.error("An unexpected error occurred while ensuring index '%s' on field '%s' for collection '%s': %s",
                    effectiveIndexName, indexKeyName, collectionName, exception.getMessage(), exception);
        }
    }
}