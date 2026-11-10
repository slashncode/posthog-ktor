package io.github.slashncode.posthog.ktor

import com.posthog.server.PostHogInterface
import com.posthog.server.PostHogRequestContext
import com.posthog.server.PostHogRequestContextData
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import org.slf4j.MDC
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** The MDC key PostHog Logs links a log line to a person by. */
public const val MDC_DISTINCT_ID: String = "posthogDistinctId"

/** The MDC key PostHog Logs links a log line to a session replay by. */
public const val MDC_SESSION_ID: String = "sessionId"

/**
 * Who a piece of work is about, for PostHog: a distinct id, a session id and properties that every
 * event captured inside it carries. The [PostHog] plugin puts one into every call's coroutine
 * context; [PostHogClient.withContext] does the same for work outside a call, and [toHeaders] /
 * [fromHeaders] carry the ids through a message queue.
 *
 * Unlike the official SDK's `PostHogRequestContext`, which lives in a ThreadLocal, this context
 * travels with the coroutine across suspensions and thread switches. Whenever the coroutine runs,
 * it is also installed as the SDK's request context (so direct SDK calls see it) and, unless turned
 * off, as the [MDC_DISTINCT_ID] and [MDC_SESSION_ID] of the log lines written meanwhile.
 */
public class PostHogContext private constructor(
    private val distinctIdOf: () -> String?,
    /** The session id, from posthog-js' `X-PostHog-Session-Id` header in a call. */
    public val sessionId: String?,
    private val propertiesOf: () -> Map<String, Any>,
    private val owner: PostHogInterface?,
    private val logContext: Boolean,
) : AbstractCoroutineContextElement(PostHogContext),
    ThreadContextElement<Any?> {
    /**
     * A context for work outside a call, e.g. a message consumer that knows whose message it
     * handles: `launch(PostHogContext(distinctId = userId)) { … }`. Captures see it; to have the
     * official SDK's `PostHogContextSpanProcessor` tag spans too, use [PostHogClient.withContext],
     * which binds it to the client. Events inside carry [groups] (group analytics) as `$groups`.
     */
    public constructor(
        distinctId: String? = null,
        sessionId: String? = null,
        properties: Map<String, Any?> = emptyMap(),
        groups: Map<String, String> = emptyMap(),
    ) : this({ distinctId }, sessionId, properties.withGroups(groups).let { { it } }, owner = null, logContext = true)

    /** The distinct id events are captured for, or null for events without a person. */
    public val distinctId: String? get() = distinctIdOf()

    /** Properties every event captured inside the context carries (`$groups` included). */
    public val properties: Map<String, Any> get() = propertiesOf()

    /** The groups every event captured inside the context belongs to, by group type. */
    @Suppress("UNCHECKED_CAST")
    public val groups: Map<String, String> get() = (properties[GROUPS_PROPERTY] as? Map<String, String>).orEmpty()

    /**
     * This context's values as they are now, detached from the call that made it, for work that
     * outlives the call: `application.launch(PostHogContext.current()!!.snapshot()) { … }`.
     */
    public fun snapshot(): PostHogContext {
        val distinctId = distinctId
        val properties = properties
        return PostHogContext({ distinctId }, sessionId, { properties }, owner, logContext)
    }

    /**
     * The ids as posthog-js' tracing headers ([DISTINCT_ID_HEADER], [SESSION_ID_HEADER]), for a
     * message to a queue or another service; the properties stay behind. [fromHeaders] reads them.
     */
    public fun toHeaders(): Map<String, String> =
        buildMap {
            distinctId?.let { put(DISTINCT_ID_HEADER, it) }
            sessionId?.let { put(SESSION_ID_HEADER, it) }
        }

    internal fun ownedBy(client: PostHogInterface?): PostHogContext =
        if (client == null || client === owner) this else PostHogContext(distinctIdOf, sessionId, propertiesOf, client, logContext)

    internal fun toRequestContextData(): PostHogRequestContextData =
        PostHogRequestContextData(
            distinctId = distinctId,
            sessionId = sessionId,
            properties =
                buildMap {
                    putAll(properties)
                    sessionId?.let { putIfAbsent(SESSION_ID_PROPERTY, it) }
                },
        )

    override fun updateThreadContext(context: CoroutineContext): Any? {
        val data = toRequestContextData()
        val scope =
            if (owner != null) {
                PostHogRequestContext.beginScope(owner, data, true)
            } else {
                PostHogRequestContext.beginScope(data, true)
            }
        val previousContext = onThread.get()
        onThread.set(this)
        if (!logContext) return ThreadState(scope, previousContext, null, null, logContext = false)
        val state = ThreadState(scope, previousContext, MDC.get(MDC_DISTINCT_ID), MDC.get(MDC_SESSION_ID), logContext = true)
        putOrRemove(MDC_DISTINCT_ID, data.distinctId)
        putOrRemove(MDC_SESSION_ID, data.sessionId)
        return state
    }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: Any?,
    ) {
        val state = oldState as ThreadState
        state.scope.close()
        onThread.set(state.previousContext)
        if (state.logContext) {
            putOrRemove(MDC_DISTINCT_ID, state.previousDistinctId)
            putOrRemove(MDC_SESSION_ID, state.previousSessionId)
        }
    }

    private class ThreadState(
        val scope: PostHogRequestContext.Scope,
        val previousContext: PostHogContext?,
        val previousDistinctId: String?,
        val previousSessionId: String?,
        val logContext: Boolean,
    )

    /** Finds the context of the current coroutine. */
    public companion object Key : CoroutineContext.Key<PostHogContext> {
        /** The context of the current coroutine, if any. */
        public suspend fun current(): PostHogContext? = currentCoroutineContext()[Key]

        /**
         * The context named by tracing headers ([toHeaders] of the sender, or posthog-js'), cleaned
         * as the official SDK cleans them; null if they name no one. Names are matched without
         * regard to case; values are strings or lists of strings (the first counts).
         */
        public fun fromHeaders(headers: Map<String, *>): PostHogContext? {
            val canonical =
                buildMap {
                    headers.forEach { (name, value) ->
                        when {
                            name.equals(DISTINCT_ID_HEADER, ignoreCase = true) -> put(DISTINCT_ID_HEADER, value)
                            name.equals(SESSION_ID_HEADER, ignoreCase = true) -> put(SESSION_ID_HEADER, value)
                        }
                    }
                }
            val data = PostHogRequestContext.fromHeaders(canonical)
            if (data.distinctId == null && data.sessionId == null) return null
            return PostHogContext(data.distinctId, data.sessionId)
        }

        internal fun forCall(
            distinctIdOf: () -> String?,
            sessionId: String?,
            propertiesOf: () -> Map<String, Any>,
            owner: PostHogInterface?,
            logContext: Boolean,
        ): PostHogContext = PostHogContext(distinctIdOf, sessionId, propertiesOf, owner, logContext)

        private const val SESSION_ID_PROPERTY = "\$session_id"

        // The context of the coroutine running on this thread, read live: the SDK's request context
        // is a copy taken when the coroutine resumed, before e.g. `call.posthog.group` changed it.
        private val onThread = ThreadLocal<PostHogContext?>()

        internal fun onThread(): PostHogContext? = onThread.get()

        /**
         * The context of the coroutine running on this thread, if any, for callbacks that cannot
         * suspend (a model library's listener, an OpenTelemetry span processor).
         */
        public fun currentThread(): PostHogContext? = onThread.get()

        private const val GROUPS_PROPERTY = "\$groups"

        private fun Map<String, Any?>.withGroups(groups: Map<String, String>): Map<String, Any> =
            if (groups.isEmpty()) withoutNulls() else withoutNulls() + (GROUPS_PROPERTY to groups)

        private fun putOrRemove(
            key: String,
            value: String?,
        ) {
            if (value == null) MDC.remove(key) else MDC.put(key, value)
        }
    }
}

internal fun Map<String, Any?>.withoutNulls(): Map<String, Any> =
    buildMap { this@withoutNulls.forEach { (key, value) -> if (value != null) put(key, value) } }
