package net.legacy.library.player.service;

import io.fairyproject.log.Log;
import lombok.AccessLevel;
import lombok.Getter;
import net.legacy.library.mongodb.model.MongoDBConnectionConfig;
import org.bson.Document;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The player and entity data services of one plugin, started together and shut down in the right order.
 *
 * <p>{@link #start(LegacyDataServiceSettings)} checks that MongoDB can be reached, then starts a
 * {@link LegacyPlayerDataService} and a {@link LegacyEntityDataService} named after
 * {@link LegacyDataServiceSettings#getName()}. If a later step fails, what was already started is shut down again,
 * so a failed start leaves no service registered and no timer running.
 *
 * <p>Everything else stays with the services: data is changed in memory (L1) and persisted by their own timers, and
 * each service's {@code shutdown()} cancels its own timers, persists all of L1 and closes its Redis client, without
 * touching the scheduler every plugin shares. The player module shuts every registered service down by itself when it
 * is disabled, but a plugin depending on it is disabled first; a plugin that closes anything the services use in its
 * own {@code onDisable}, such as its MongoDB connection, must call {@link #shutdown()} before that, which is what this
 * class is for.
 *
 * <p>Instances are thread-safe; {@link #shutdown()} runs once, however often and from however many threads it is
 * called. Call it from a platform thread, such as the one that disables the plugin, because it waits for the final
 * persistence runs.
 *
 * @author qwq-dev
 * @since 2026-11-09 12:00
 */
@Getter
public class LegacyDataServices {

    private final String name;
    private final MongoDBConnectionConfig mongoDBConnectionConfig;

    /**
     * The player data service, or {@code null} when the settings did not ask for one.
     */
    private final LegacyPlayerDataService playerDataService;

    /**
     * The entity data service, or {@code null} when the settings did not ask for one.
     */
    private final LegacyEntityDataService entityDataService;

    @Getter(AccessLevel.NONE)
    private final boolean closeMongoDBConnection;

    @Getter(AccessLevel.NONE)
    private final AtomicBoolean shutdown = new AtomicBoolean();

    private LegacyDataServices(LegacyDataServiceSettings settings, LegacyPlayerDataService playerDataService,
                               LegacyEntityDataService entityDataService) {
        this.name = settings.getName();
        this.mongoDBConnectionConfig = settings.getMongoDBConnectionConfig();
        this.closeMongoDBConnection = settings.isCloseMongoDBConnection();
        this.playerDataService = playerDataService;
        this.entityDataService = entityDataService;
    }

    /**
     * Starts the services the settings ask for.
     *
     * @param settings the settings
     * @return the started services
     * @throws IllegalArgumentException if the name is blank or the MongoDB or Redis configuration is missing
     * @throws IllegalStateException    if MongoDB cannot be reached, with the driver's exception as the cause; a
     *                                  connection the settings let this class close is closed first
     * @throws RuntimeException         if a service cannot be started, such as one with the same name already
     *                                  running; whatever was started before is shut down first
     */
    public static LegacyDataServices start(LegacyDataServiceSettings settings) {
        if (settings.getName() == null || settings.getName().isBlank()) {
            throw new IllegalArgumentException("The name of the data services must not be null or blank.");
        }

        if (settings.getMongoDBConnectionConfig() == null || settings.getRedisConfig() == null) {
            throw new IllegalArgumentException("The MongoDB connection config and the Redis config must not be null.");
        }

        if (settings.isVerifyConnection()) {
            try {
                verifyConnection(settings.getMongoDBConnectionConfig());
            } catch (RuntimeException exception) {
                closeIfOwned(settings);
                throw new IllegalStateException("MongoDB cannot be reached for data services " + settings.getName(),
                        exception);
            }
        }

        LegacyPlayerDataService playerDataService = null;
        try {
            if (settings.isPlayerDataService()) {
                playerDataService = LegacyPlayerDataService.of(
                        settings.getName() + "-player-data-service",
                        settings.getMongoDBConnectionConfig(), settings.getRedisConfig(),
                        settings.getAutoSaveInterval(), settings.getBasePackages(), settings.getClassLoaders(),
                        settings.getRedisStreamAcceptInterval(), settings.getPlayerTtl(),
                        settings.getPlayerStreamRetention()
                );
            }

            LegacyEntityDataService entityDataService = null;
            if (settings.isEntityDataService()) {
                entityDataService = LegacyEntityDataService.of(
                        settings.getName() + "-entity-data-service",
                        settings.getMongoDBConnectionConfig(), settings.getRedisConfig(),
                        settings.getAutoSaveInterval(), settings.getBasePackages(), settings.getClassLoaders(),
                        settings.getRedisStreamAcceptInterval(), settings.getEntityTtl(),
                        settings.getEntitySyncSettings()
                );
            }

            return new LegacyDataServices(settings, playerDataService, entityDataService);
        } catch (RuntimeException exception) {
            // A failed start must not leave a registered service with running timers behind
            new LegacyDataServices(settings, playerDataService, null).shutdown();
            throw exception;
        }
    }

    /**
     * Pings the database of a MongoDB connection.
     *
     * @param mongoDBConnectionConfig the connection
     * @throws RuntimeException the driver's exception if the database cannot be reached, typically after the
     *                          connection's server selection timeout (30 seconds unless the URI sets
     *                          {@code serverSelectionTimeoutMS})
     */
    public static void verifyConnection(MongoDBConnectionConfig mongoDBConnectionConfig) {
        mongoDBConnectionConfig.getDatastore().getDatabase().runCommand(new Document("ping", 1));
    }

    /**
     * Creates the Redis configuration of a single server.
     *
     * @param address  the address, such as {@code redis://127.0.0.1:6379}
     * @param database the database number
     * @param password the password, or {@code null} or blank for none
     * @return a new configuration; thread counts are left to the cache module's cap
     */
    public static Config singleServerRedisConfig(String address, int database, String password) {
        Config config = new Config();
        SingleServerConfig singleServerConfig = config.useSingleServer()
                .setAddress(address)
                .setDatabase(database);
        if (password != null && !password.isBlank()) {
            singleServerConfig.setPassword(password);
        }
        return config;
    }

    private static void closeIfOwned(LegacyDataServiceSettings settings) {
        if (settings.isCloseMongoDBConnection()) {
            try {
                settings.getMongoDBConnectionConfig().close();
            } catch (Exception exception) {
                Log.error("Failed to close the MongoDB connection of data services %s", exception, settings.getName());
            }
        }
    }

    /**
     * Runs one shutdown step, its failure logged and contained.
     *
     * @return whether the step was interrupted, or left an interrupt pending; the caller restores it at the end
     */
    private static boolean shutdownStep(String what, ShutdownStep step) {
        try {
            step.run();
            return Thread.interrupted();
        } catch (InterruptedException exception) {
            Log.error("Interrupted while shutting down %s", exception, what);
            return true;
        } catch (Exception exception) {
            Log.error("Unexpected error shutting down %s", exception, what);
            return Thread.interrupted();
        }
    }

    /**
     * Checks whether {@link #shutdown()} has been called.
     *
     * @return {@code true} once shutdown has started
     */
    public boolean isShutdown() {
        return shutdown.get();
    }

    /**
     * Shuts the player service down, then the entity service, then closes the MongoDB connection when the settings
     * said so; each service persists all of its L1 before it closes its Redis client.
     *
     * <p>Every step catches and logs its own failure, so one service failing never keeps the other from persisting or
     * the connection from closing, as the player module's own shutdown does. An interrupt, whether pending on entry or
     * raised by a step, is restored only after every step has run: restored at once, it would cut the next service's
     * final persistence short. Runs once; later calls return at once.
     */
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }

        long start = System.currentTimeMillis();
        boolean interrupted = Thread.interrupted();

        if (playerDataService != null) {
            interrupted |= shutdownStep("player data service " + playerDataService.getName(), playerDataService::shutdown);
        }

        if (entityDataService != null) {
            interrupted |= shutdownStep("entity data service " + entityDataService.getName(), entityDataService::shutdown);
        }

        if (closeMongoDBConnection && mongoDBConnectionConfig != null) {
            interrupted |= shutdownStep("MongoDB connection of " + name, mongoDBConnectionConfig::close);
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        Log.info("Data services %s have been shut down. Total time: %s ms", name, System.currentTimeMillis() - start);
    }

    @FunctionalInterface
    private interface ShutdownStep {

        void run() throws InterruptedException;

    }

}
