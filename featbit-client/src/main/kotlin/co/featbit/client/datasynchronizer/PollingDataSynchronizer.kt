package co.featbit.client.datasynchronizer

import co.featbit.client.internal.GetUserFlags
import co.featbit.client.model.FBUser
import co.featbit.client.options.FBOptions
import co.featbit.client.store.MemoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * Periodically polls the FeatBit evaluation server for the current user's feature flags and
 * upserts them into the [store]. The Kotlin analogue of the .NET `PollingDataSynchronizer`,
 * with the background loop running as a coroutine on [scope].
 */
internal class PollingDataSynchronizer(
    options: FBOptions,
    user: FBUser,
    private val store: MemoryStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val getUserFlags: GetUserFlags = GetUserFlags(options, user),
) : DataSynchronizer {

    private val logger = options.logger
    private val pollingInterval: Duration = options.pollingInterval
    private val userKey: String = user.key

    private val startTask = CompletableDeferred<Boolean>()
    private val initializedFlag = AtomicBoolean(false)

    @Volatile
    private var timestamp: Long = 0

    override val initialized: Boolean get() = initializedFlag.get()

    override suspend fun start(): Boolean {
        scope.launch { pollingLoop() }
        return startTask.await()
    }

    private suspend fun pollingLoop() {
        while (scope.isActive) {
            safePoll()
            try {
                logger.debug { "Waiting for the next polling interval of $pollingInterval." }
                delay(pollingInterval)
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (ex: Exception) {
                logger.error("An unexpected error occurred while waiting for the next polling interval.", ex)
            }
        }
    }

    private suspend fun safePoll() {
        try {
            val response = getUserFlags.run(timestamp)

            if (response.isFatal) {
                logger.error(
                    "Polling data synchronizer encountered fatal HTTP error ${response.statusCode}. Stop polling...",
                )
                startTask.complete(false)
                close()
                return
            }

            if (response.isError) {
                logger.warn("Polling data synchronizer encountered transient HTTP error ${response.statusCode}.")
                return
            }

            timestamp = System.currentTimeMillis()
            logger.debug { "Polling received ${response.flags.size} flags." }

            response.flags.forEach { store.upsert(it) }

            if (initializedFlag.compareAndSet(false, true)) {
                startTask.complete(true)
                logger.info("Polling data synchronizer initialized for user $userKey.")
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.error("Exception occurred while polling data.", ex)
        }
    }

    override fun close() {
        scope.cancel()
        getUserFlags.close()
        // Ensure a never-initialized synchronizer doesn't leave start() suspended forever.
        startTask.complete(false)
    }
}
