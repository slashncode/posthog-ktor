package io.github.slashncode.posthog.ktor

// Surveys answered outside posthog-js (a server-rendered form, an email link), recorded as
// posthog-js records them: "survey shown", "survey sent", "survey dismissed".

/** A survey question as shown, for `$survey_questions`. */
public class PostHogSurveyQuestion(
    /** The question's id in PostHog. */
    public val id: String,
    /** The question's text as the person saw it. */
    public val question: String,
)

/** Records that the survey [surveyId] was shown to [distinctId], or to the current context's person. */
public fun PostHogClient.captureSurveyShown(
    surveyId: String,
    iteration: Int? = null,
    distinctId: String? = null,
) {
    capture(SURVEY_SHOWN, surveyProperties(surveyId, iteration), distinctId)
}

/**
 * Records answers to the survey [surveyId]: [responses] by question id (text, a rating as text, or
 * a list of choices), as `$survey_response_<questionId>`. [completed] false sends a partial
 * response; later sends with the same [submissionId] complete it. Marks the person as having
 * responded (`$survey_responded/<surveyId>`), so the survey's "don't show again" conditions work.
 */
public fun PostHogClient.captureSurveySent(
    surveyId: String,
    responses: Map<String, Any?>,
    questions: List<PostHogSurveyQuestion> = emptyList(),
    iteration: Int? = null,
    submissionId: String? = null,
    completed: Boolean = true,
    distinctId: String? = null,
) {
    val properties =
        surveyProperties(surveyId, iteration) +
            responseProperties(responses, questions) +
            mapOf(
                SUBMISSION_ID to submissionId,
                COMPLETED to completed,
                SET to mapOf(interaction("responded", surveyId, iteration) to true),
            )
    capture(SURVEY_SENT, properties, distinctId)
}

/**
 * Records that the survey [surveyId] was closed without an answer (or with the partial
 * [responses]), and marks the person (`$survey_dismissed/<surveyId>`).
 */
public fun PostHogClient.captureSurveyDismissed(
    surveyId: String,
    responses: Map<String, Any?> = emptyMap(),
    iteration: Int? = null,
    submissionId: String? = null,
    distinctId: String? = null,
) {
    val properties =
        surveyProperties(surveyId, iteration) +
            responseProperties(responses, emptyList()) +
            mapOf(
                SUBMISSION_ID to submissionId,
                PARTIALLY_COMPLETED to responses.values.any { it != null },
                SET to mapOf(interaction("dismissed", surveyId, iteration) to true),
            )
    capture(SURVEY_DISMISSED, properties, distinctId)
}

/** Records that the survey [surveyId] was shown to the call's person. */
public fun PostHogCall.captureSurveyShown(
    surveyId: String,
    iteration: Int? = null,
) {
    capture(SURVEY_SHOWN, surveyProperties(surveyId, iteration))
}

/** Records the call's person's answers to the survey [surveyId]; see [PostHogClient.captureSurveySent]. */
public fun PostHogCall.captureSurveySent(
    surveyId: String,
    responses: Map<String, Any?>,
    questions: List<PostHogSurveyQuestion> = emptyList(),
    iteration: Int? = null,
    submissionId: String? = null,
    completed: Boolean = true,
) {
    val id = distinctId
    val properties =
        surveyProperties(surveyId, iteration) +
            responseProperties(responses, questions) +
            mapOf(
                SUBMISSION_ID to submissionId,
                COMPLETED to completed,
                SET to mapOf(interaction("responded", surveyId, iteration) to true),
            )
    client.capture(SURVEY_SENT, properties, id, personContext()) { null }
}

/** Records that the call's person closed the survey [surveyId]; see [PostHogClient.captureSurveyDismissed]. */
public fun PostHogCall.captureSurveyDismissed(
    surveyId: String,
    responses: Map<String, Any?> = emptyMap(),
    iteration: Int? = null,
    submissionId: String? = null,
) {
    val properties =
        surveyProperties(surveyId, iteration) +
            responseProperties(responses, emptyList()) +
            mapOf(
                SUBMISSION_ID to submissionId,
                PARTIALLY_COMPLETED to responses.values.any { it != null },
                SET to mapOf(interaction("dismissed", surveyId, iteration) to true),
            )
    client.capture(SURVEY_DISMISSED, properties, distinctId, personContext()) { null }
}

private const val SURVEY_SHOWN = "survey shown"
private const val SURVEY_SENT = "survey sent"
private const val SURVEY_DISMISSED = "survey dismissed"
private const val SUBMISSION_ID = "\$survey_submission_id"
private const val COMPLETED = "\$survey_completed"
private const val PARTIALLY_COMPLETED = "\$survey_partially_completed"
private const val SET = "\$set"

private fun surveyProperties(
    surveyId: String,
    iteration: Int?,
): Map<String, Any?> = mapOf("\$survey_id" to surveyId, "\$survey_iteration" to iteration)

// Ratings go as text and choices as lists of text, as PostHog's survey results expect.
private fun responseProperties(
    responses: Map<String, Any?>,
    questions: List<PostHogSurveyQuestion>,
): Map<String, Any?> {
    val answers = responses.mapValues { (_, answer) -> answer?.let(::surveyAnswer) }
    return buildMap {
        answers.forEach { (questionId, answer) -> put("\$survey_response_$questionId", answer) }
        if (questions.isNotEmpty()) {
            put(
                "\$survey_questions",
                questions.map { question ->
                    mapOf("id" to question.id, "question" to question.question, "response" to answers[question.id]).filterValues {
                        it !=
                            null
                    }
                },
            )
        }
    }
}

private fun surveyAnswer(answer: Any): Any =
    when (answer) {
        is Collection<*> -> answer.mapNotNull { it?.toString() }
        else -> answer.toString()
    }

private fun interaction(
    action: String,
    surveyId: String,
    iteration: Int?,
): String = if (iteration != null && iteration > 0) "\$survey_$action/$surveyId/$iteration" else "\$survey_$action/$surveyId"
