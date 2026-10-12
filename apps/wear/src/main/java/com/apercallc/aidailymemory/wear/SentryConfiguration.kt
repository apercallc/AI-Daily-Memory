package com.apercallc.aidailymemory.wear

import android.content.Context
import io.sentry.KeyValueCollectionBehavior
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid

internal object SentryConfiguration {
    fun initialize(context: Context) {
        val dsn = context.getString(R.string.sentry_dsn)
        if (dsn.isBlank()) return

        SentryAndroid.init(context.applicationContext) { options ->
            options.dsn = dsn
            options.environment = if (BuildConfig.DEBUG) "development" else "production"
            options.release =
                "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            options.dataCollection.userInfo = false
            options.dataCollection.filePaths = false
            options.dataCollection.urlQueryParams = KeyValueCollectionBehavior.off()
            options.maxBreadcrumbs = 0
            options.tracesSampleRate = 0.0
            options.profilesSampleRate = 0.0
            options.sessionReplay.sessionSampleRate = 0.0
            options.sessionReplay.onErrorSampleRate = 0.0
            options.isEnableUserInteractionTracing = false
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.isEnableAutoSessionTracking = false
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> scrubEvent(event) }
        }
    }

    internal fun scrubEvent(event: SentryEvent): SentryEvent {
        event.message = null
        event.request = null
        event.user = null
        event.transaction = null
        event.serverName = null
        event.debugMeta = null
        event.unknown?.clear()
        event.threads = null
        event.contexts.clear()
        event.breadcrumbs = emptyList()
        event.extras?.clear()
        event.tags?.clear()
        event.exceptions?.forEach { exception ->
            exception.value = null
            exception.unknown?.clear()
            exception.stacktrace?.let { stacktrace ->
                stacktrace.unknown?.clear()
                stacktrace.frames?.forEach { frame ->
                    frame.filename?.let { filename ->
                        val normalizedPath = filename.replace('\\', '/')
                        if (
                            normalizedPath.startsWith('/') ||
                                (normalizedPath.length > 2 && normalizedPath[1] == ':' && normalizedPath[2] == '/')
                        ) {
                            frame.filename = normalizedPath.substringAfterLast('/')
                        }
                    }
                    frame.absPath = null
                    frame.preContext = null
                    frame.contextLine = null
                    frame.postContext = null
                    frame.vars = null
                    frame.unknown?.clear()
                }
            }
        }
        return event
    }
}
