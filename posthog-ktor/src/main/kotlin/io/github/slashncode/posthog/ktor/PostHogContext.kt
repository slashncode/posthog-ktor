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
 * context; [withPostHogContext] does the same for work outside a call.
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
    /** A context for work outside a call, e.g. a message consumer that knows whose message it handles. */
    public constructor(
        distinctId: String? = null,
        sessionId: String? = null,
        properties: Map<String, Any?> = emptyMap(),
    ) : this({ distinctId }, sessionId, properties.withoutNulls().let { { it } }, owner = null, logContext = true)

    /** The distinct id events are captured for, or null for events without a person. */
    public val distinctId: String? get() = distinctIdOf()

    /** Properties every event captured inside the context carries. */
    public val properties: Map<String, Any> get() = propertiesOf()

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
        if (!logContext) return ThreadState(scope, null, null, logContext = false)
        val state = ThreadState(scope, MDC.get(MDC_DISTINCT_ID), MDC.get(MDC_SESSION_ID), logContext = true)
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
        if (state.logContext) {
            putOrRemove(MDC_DISTINCT_ID, state.previousDistinctId)
            putOrRemove(MDC_SESSION_ID, state.previousSessionId)
        }
    }

    private class ThreadState(
        val scope: PostHogRequestContext.Scope,
        val previousDistinctId: String?,
        val previousSessionId: String?,
        val logContext: Boolean,
    )

    /** Finds the context of the current coroutine. */
    public companion object Key : CoroutineContext.Key<PostHogContext> {
        /** The context of the current coroutine, if any. */
        public suspend fun current(): PostHogContext? = currentCoroutineContext()[Key]

        internal fun forCall(
            distinctIdOf: () -> String?,
            sessionId: String?,
            propertiesOf: () -> Map<String, Any>,
            owner: PostHogInterface?,
            logContext: Boolean,
        ): PostHogContext = PostHogContext(distinctIdOf, sessionId, propertiesOf, owner, logContext)

        private const val SESSION_ID_PROPERTY = "\$session_id"

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
