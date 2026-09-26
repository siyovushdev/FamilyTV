package ru.familytv.voice

/**
 * TV-friendly microphone permission decision helper.
 * Does not touch VoiceRuntime — callers request Android permission separately.
 */
enum class MicPermissionAction {
    /** Already granted — proceed with voice features. */
    PROCEED,
    /** Show a short explanation, then request the runtime permission. */
    EXPLAIN_THEN_REQUEST,
    /** Permanently denied / don't-ask-again — show fallback UI, allow Back. */
    SHOW_UNAVAILABLE,
}

object MicPermissionGate {
    fun nextAction(
        hasPermission: Boolean,
        alreadyAskedThisSession: Boolean,
        shouldShowRationale: Boolean,
    ): MicPermissionAction = when {
        hasPermission -> MicPermissionAction.PROCEED
        !alreadyAskedThisSession -> MicPermissionAction.EXPLAIN_THEN_REQUEST
        shouldShowRationale -> MicPermissionAction.EXPLAIN_THEN_REQUEST
        else -> MicPermissionAction.SHOW_UNAVAILABLE
    }

    const val CHILD_MESSAGE = "Микрофон недоступен"
    const val EXPLAIN_TITLE = "Нужен микрофон"
    const val EXPLAIN_BODY = "Скажи ответ вслух. Разреши микрофон, когда появится запрос."
    const val RETRY_LABEL = "Разрешить"
    const val BACK_LABEL = "Назад"
    const val OPEN_SETTINGS_LABEL = "Открыть настройки"
}
