package com.playfieldportal.core.ui.notification

import javax.inject.Qualifier

/**
 * The app-wide scope background work reported to [BackgroundTaskCenter] runs in.
 *
 * Work started from a screen (a Library Manager scan) used to run in that screen's
 * `viewModelScope`, so leaving Settings killed it mid-way and stranded its running row. The tray
 * owns that work now — it is stopped from the panel, not by navigating away — so it runs here.
 * Bound in the app module (SupervisorJob + Dispatchers.Default).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TaskScope
