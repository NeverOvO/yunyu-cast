package io.github.jqssun.airplay.onboarding

enum class AutoLaunchStep { NONE, INTRO, LATER, ENABLED, UNAVAILABLE }

/** Only explicit choices are persisted. Merely hiding the dialog during casting is not a refusal. */
data class AutoLaunchOnboarding(
    val handled: Boolean,
    val waitingForSettings: Boolean = false,
    val step: AutoLaunchStep = AutoLaunchStep.NONE,
) {
    fun defer() = copy(handled = true, waitingForSettings = false, step = AutoLaunchStep.LATER)
    fun openSettings() = copy(handled = true, waitingForSettings = true, step = AutoLaunchStep.NONE)
    fun settingsUnavailable() = copy(handled = true, waitingForSettings = false, step = AutoLaunchStep.UNAVAILABLE)
    fun settingsReturned(granted: Boolean) = copy(
        handled = true, waitingForSettings = false,
        step = if (granted) AutoLaunchStep.ENABLED else AutoLaunchStep.LATER,
    )
    fun dismiss() = copy(handled = true, waitingForSettings = false, step = AutoLaunchStep.NONE)

    companion object {
        fun restore(
            granted: Boolean,
            handled: Boolean,
            waitingForSettings: Boolean,
            savedStep: AutoLaunchStep? = null,
        ): AutoLaunchOnboarding = when {
            waitingForSettings -> AutoLaunchOnboarding(true, waitingForSettings = true)
            savedStep == AutoLaunchStep.ENABLED -> AutoLaunchOnboarding(true,
                step = if (granted) AutoLaunchStep.ENABLED else AutoLaunchStep.LATER)
            granted -> AutoLaunchOnboarding(true)
            savedStep == AutoLaunchStep.LATER || savedStep == AutoLaunchStep.UNAVAILABLE ->
                AutoLaunchOnboarding(true, step = savedStep)
            granted || handled -> AutoLaunchOnboarding(true)
            else -> AutoLaunchOnboarding(false, step = AutoLaunchStep.INTRO)
        }
    }
}
