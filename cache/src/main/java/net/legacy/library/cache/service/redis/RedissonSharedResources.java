package net.legacy.library.cache.service.redis;

import io.fairyproject.log.Log;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.redisson.config.Config;
import org.redisson.config.TransportMode;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded Redisson threads for this library: a cap on each client's own pools, and optionally one Netty event loop
 * group and one worker pool shared by the clients that opt in.
 *
 * <p>Left alone, every {@code Redisson.create} starts its own event loop group of {@code nettyThreads} (32 by
 * default) and its own pool of {@code threads} (16 by default), so a server with seven Redis services runs
 * 7 x 48 Redisson threads. Handing each client the same two pools keeps the total at
 * {@value #DEFAULT_NETTY_THREADS} + {@value #DEFAULT_THREADS} however many services there are; each client still
 * keeps its own connections and its own timer thread.
 *
 * <p>Redisson never shuts down a group or pool it was given, so a client's {@code shutdown()} leaves them running for
 * the others; {@link #shutdown()} stops them when the cache module itself is disabled. A {@link Config} that already
 * carries its own event loop group or executor, or uses a transport other than NIO, is passed through unchanged.
 *
 * <p>By default every client is capped ({@link #limit(Config)}): a {@link Config} still on Redisson's default thread
 * counts gets {@value #DEFAULT_NETTY_THREADS} Netty threads and {@value #DEFAULT_THREADS} worker threads, while
 * counts the caller set are kept. {@value #LIMIT_PROPERTY}{@code =false} turns the cap off.
 *
 * <p>Sharing is optional: a {@code RedisCacheService} opts in with its {@code shareThreads} constructor argument, or
 * every one does when the system property {@value #ENABLED_PROPERTY} is {@code true}. The sizes of both the cap and
 * the shared pools can be set with the system properties {@value #NETTY_THREADS_PROPERTY} and
 * {@value #THREADS_PROPERTY}.
 *
 * @author qwq-dev
 * @since 2026-10-23 12:00
 */
public final class RedissonSharedResources {

    /**
     * System property that turns off the default cap on each client's thread counts when set to {@code false}.
     */
    public static final String LIMIT_PROPERTY = "legacy.cache.redisson.limitThreads";

    /**
     * System property that makes every {@code RedisCacheService} built without an explicit choice share the pools.
     */
    public static final String ENABLED_PROPERTY = "legacy.cache.redisson.sharedThreads";

    /**
     * System property for the number of shared Netty event loop threads.
     */
    public static final String NETTY_THREADS_PROPERTY = "legacy.cache.redisson.nettyThreads";

    /**
     * System property for the number of shared Redisson worker threads.
     */
    public static final String THREADS_PROPERTY = "legacy.cache.redisson.threads";

    /**
     * Default number of Netty event loop threads.
     */
    public static final int DEFAULT_NETTY_THREADS = 4;

    /**
     * Default number of Redisson worker threads.
     */
    public static final int DEFAULT_THREADS = 8;

    private static final ReentrantLock LOCK = new ReentrantLock();

    private static EventLoopGroup eventLoopGroup;
    private static ExecutorService executor;

    private RedissonSharedResources() {
    }

    /**
     * Whether a {@code RedisCacheService} built without an explicit choice shares the pools: the value of
     * {@value #ENABLED_PROPERTY}, off unless set to {@code true}.
     *
     * @return whether sharing is on by default
     */
    public static boolean enabledByDefault() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    /**
     * Whether each client's thread counts are capped: on unless {@value #LIMIT_PROPERTY} is {@code false}.
     *
     * @return whether the cap applies
     */
    public static boolean limitEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(LIMIT_PROPERTY, "true").trim());
    }

    /**
     * Returns a copy of {@code config} whose thread counts still on Redisson's defaults are capped at
     * {@value #NETTY_THREADS_PROPERTY} (default {@value #DEFAULT_NETTY_THREADS}) and {@value #THREADS_PROPERTY}
     * (default {@value #DEFAULT_THREADS}). Counts the caller changed are kept, and the caller's {@link Config} is never
     * modified. Returns {@code config} itself when the cap is turned off or nothing needs capping.
     *
     * @param config the Redisson configuration
     * @return the configuration to hand to {@code Redisson.create}
     */
    public static Config limit(Config config) {
        if (!limitEnabled()) {
            return config;
        }

        Config defaults = new Config();
        boolean nettyDefault = config.getNettyThreads() == defaults.getNettyThreads();
        boolean threadsDefault = config.getThreads() == defaults.getThreads();
        if (!nettyDefault && !threadsDefault) {
            return config;
        }

        Config limited = new Config(config);
        if (nettyDefault) {
            limited.setNettyThreads(size(NETTY_THREADS_PROPERTY, DEFAULT_NETTY_THREADS));
        }
        if (threadsDefault) {
            limited.setThreads(size(THREADS_PROPERTY, DEFAULT_THREADS));
        }
        return limited;
    }

    /**
     * Returns a copy of {@code config} that uses the shared event loop group and worker pool, creating them on first
     * use. The caller's {@link Config} is never modified.
     *
     * @param config the Redisson configuration
     * @return the configuration to hand to {@code Redisson.create}
     */
    public static Config share(Config config) {
        if (config.getEventLoopGroup() != null || config.getExecutor() != null
                || config.getTransportMode() != TransportMode.NIO) {
            return config;
        }

        Config shared = new Config(config);

        LOCK.lock();
        try {
            if (eventLoopGroup == null) {
                eventLoopGroup = new NioEventLoopGroup(size(NETTY_THREADS_PROPERTY, DEFAULT_NETTY_THREADS),
                        new DefaultThreadFactory("legacy-redisson-netty", true));
            }
            if (executor == null) {
                executor = Executors.newFixedThreadPool(size(THREADS_PROPERTY, DEFAULT_THREADS),
                        new DefaultThreadFactory("legacy-redisson", true));
            }
            shared.setEventLoopGroup(eventLoopGroup);
            shared.setExecutor(executor);
        } finally {
            LOCK.unlock();
        }

        return shared;
    }

    /**
     * Stops the shared event loop group and worker pool. Clients created afterwards get new ones.
     */
    public static void shutdown() {
        LOCK.lock();
        try {
            if (executor != null) {
                executor.shutdown();
                executor = null;
            }
            if (eventLoopGroup != null) {
                eventLoopGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
                eventLoopGroup = null;
            }
        } catch (Exception exception) {
            Log.error("Error shutting down the shared Redisson threads", exception);
        } finally {
            LOCK.unlock();
        }
    }

    private static int size(String property, int fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Falls through to the warning below
        }
        Log.warn("Ignoring %s=%s, using %d", property, value, fallback);
        return fallback;
    }

}
