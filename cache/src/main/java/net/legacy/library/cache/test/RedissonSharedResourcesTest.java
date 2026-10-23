package net.legacy.library.cache.test;

import net.legacy.library.cache.service.redis.RedissonSharedResources;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;
import org.redisson.config.Config;
import org.redisson.config.TransportMode;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Test class for the optional Redisson thread pools Legacy Redis services can share.
 *
 * <p>Needs no Redis server: it checks the configuration handed to {@code Redisson.create}, which decides how many
 * threads each client starts.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2026-10-23 12:00
 */
@ModuleTest(
        testName = "redisson-shared-resources-test",
        description = "Tests that Redisson clients share one bounded event loop group and worker pool",
        tags = {"cache", "redis", "threads"},
        priority = 2,
        timeout = 5000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class RedissonSharedResourcesTest {

    /**
     * Tests that a configuration on Redisson's default thread counts is capped, counts the caller set are kept, and
     * the caller's configuration is untouched.
     */
    public static boolean testDefaultCountsAreCapped() {
        try {
            Config plain = singleServer();
            Config capped = RedissonSharedResources.limit(plain);
            boolean cappedDefaults = capped != plain
                    && capped.getNettyThreads() == RedissonSharedResources.DEFAULT_NETTY_THREADS
                    && capped.getThreads() == RedissonSharedResources.DEFAULT_THREADS;
            Config defaults = new Config();
            boolean callerUntouched = plain.getNettyThreads() == defaults.getNettyThreads()
                    && plain.getThreads() == defaults.getThreads();

            Config own = singleServer().setNettyThreads(2).setThreads(3);
            boolean ownKept = RedissonSharedResources.limit(own) == own;

            Config half = singleServer().setThreads(3);
            Config halfCapped = RedissonSharedResources.limit(half);
            boolean halfKept = halfCapped.getThreads() == 3
                    && halfCapped.getNettyThreads() == RedissonSharedResources.DEFAULT_NETTY_THREADS;

            TestLogger.logInfo("cache", "Cap test: cappedDefaults=%s, callerUntouched=%s, ownKept=%s, halfKept=%s",
                    cappedDefaults, callerUntouched, ownKept, halfKept);

            return cappedDefaults && callerUntouched && ownKept && halfKept;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Cap test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests that the cap can be turned off with its system property.
     */
    public static boolean testCapCanBeTurnedOff() {
        String before = System.getProperty(RedissonSharedResources.LIMIT_PROPERTY);
        try {
            System.setProperty(RedissonSharedResources.LIMIT_PROPERTY, "false");
            Config plain = singleServer();
            boolean off = RedissonSharedResources.limit(plain) == plain;

            TestLogger.logInfo("cache", "Cap off test: off=%s", off);

            return off;
        } finally {
            if (before == null) {
                System.clearProperty(RedissonSharedResources.LIMIT_PROPERTY);
            } else {
                System.setProperty(RedissonSharedResources.LIMIT_PROPERTY, before);
            }
        }
    }

    /**
     * Tests that sharing is off unless the system property turns it on.
     */
    public static boolean testOffByDefault() {
        String before = System.getProperty(RedissonSharedResources.ENABLED_PROPERTY);
        try {
            System.clearProperty(RedissonSharedResources.ENABLED_PROPERTY);
            boolean offWhenUnset = !RedissonSharedResources.enabledByDefault();
            System.setProperty(RedissonSharedResources.ENABLED_PROPERTY, "true");
            boolean onWhenSet = RedissonSharedResources.enabledByDefault();

            TestLogger.logInfo("cache", "Default test: offWhenUnset=%s, onWhenSet=%s", offWhenUnset, onWhenSet);

            return offWhenUnset && onWhenSet;
        } finally {
            if (before == null) {
                System.clearProperty(RedissonSharedResources.ENABLED_PROPERTY);
            } else {
                System.setProperty(RedissonSharedResources.ENABLED_PROPERTY, before);
            }
        }
    }

    /**
     * Tests that two configurations get the same event loop group and worker pool, and the callers' are untouched.
     */
    public static boolean testConfigurationsSharePools() {
        try {
            Config first = singleServer();
            Config second = singleServer();

            Config sharedFirst = RedissonSharedResources.share(first);
            Config sharedSecond = RedissonSharedResources.share(second);

            boolean shared = sharedFirst.getEventLoopGroup() != null
                    && sharedFirst.getEventLoopGroup() == sharedSecond.getEventLoopGroup()
                    && sharedFirst.getExecutor() != null
                    && sharedFirst.getExecutor() == sharedSecond.getExecutor();
            boolean callersUntouched = sharedFirst != first && first.getEventLoopGroup() == null
                    && first.getExecutor() == null;
            boolean bounded = countLoops(sharedFirst) == RedissonSharedResources.DEFAULT_NETTY_THREADS;

            TestLogger.logInfo("cache", "Shared pools test: shared=%s, callersUntouched=%s, bounded=%s",
                    shared, callersUntouched, bounded);

            return shared && callersUntouched && bounded;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Shared pools test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests that a configuration with its own executor, or another transport, is passed through unchanged.
     */
    public static boolean testOwnResourcesPassThrough() {
        ExecutorService own = Executors.newSingleThreadExecutor();
        try {
            Config withExecutor = singleServer().setExecutor(own);
            Config epoll = singleServer().setTransportMode(TransportMode.EPOLL);

            boolean executorKept = RedissonSharedResources.share(withExecutor) == withExecutor
                    && withExecutor.getEventLoopGroup() == null;
            boolean transportKept = RedissonSharedResources.share(epoll) == epoll
                    && epoll.getEventLoopGroup() == null;

            TestLogger.logInfo("cache", "Pass-through test: executorKept=%s, transportKept=%s",
                    executorKept, transportKept);

            return executorKept && transportKept;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Pass-through test failed: %s", exception.getMessage());
            return false;
        } finally {
            own.shutdownNow();
        }
    }

    /**
     * Tests that shutdown stops the shared pools and the next client gets new ones.
     */
    public static boolean testShutdownStopsAndRecreates() {
        try {
            Config before = RedissonSharedResources.share(singleServer());
            RedissonSharedResources.shutdown();

            boolean stopped = before.getExecutor().isShutdown() && before.getEventLoopGroup().isShuttingDown();

            Config after = RedissonSharedResources.share(singleServer());
            boolean recreated = after.getEventLoopGroup() != before.getEventLoopGroup()
                    && !after.getExecutor().isShutdown();

            RedissonSharedResources.shutdown();

            TestLogger.logInfo("cache", "Shutdown test: stopped=%s, recreated=%s", stopped, recreated);

            return stopped && recreated;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Shutdown test failed: %s", exception.getMessage());
            return false;
        }
    }

    private static Config singleServer() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        return config;
    }

    private static int countLoops(Config config) {
        int loops = 0;
        for (Object ignored : config.getEventLoopGroup()) {
            loops++;
        }
        return loops;
    }

}
