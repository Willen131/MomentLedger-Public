package com.momentledger.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManifestOfflineAuditTest {

    private fun readManifest(): String =
        File("src/main/AndroidManifest.xml").readText()

    @Test
    fun sourceManifestNeverGrantsNetworkPermissionsAndStripsTransitiveOnes() {
        val manifest = readManifest()

        assertTrue(
            "INTERNET must be explicitly stripped with tools:node=\"remove\"",
            manifest.contains("""android.permission.INTERNET" tools:node="remove"""")
        )
        assertTrue(
            "ACCESS_NETWORK_STATE must be explicitly stripped with tools:node=\"remove\"",
            manifest.contains("""android.permission.ACCESS_NETWORK_STATE" tools:node="remove"""")
        )

        assertNetworkPermissionIsOnlyRemoved(manifest, "android.permission.INTERNET")
        assertNetworkPermissionIsOnlyRemoved(manifest, "android.permission.ACCESS_NETWORK_STATE")
    }

    private fun assertNetworkPermissionIsOnlyRemoved(manifest: String, permission: String) {
        val declarations = manifest.lineSequence()
            .filter { line -> line.contains("<uses-permission") && line.contains(permission) }
            .toList()

        assertFalse(
            "$permission must be declared (as a removal) in the source manifest",
            declarations.isEmpty()
        )
        declarations.forEach { line ->
            assertTrue(
                "$permission must never be granted; every declaration needs tools:node=\"remove\": $line",
                line.contains("""tools:node="remove"""")
            )
        }
    }

    @Test
    fun manifestHasZeroBackgroundCapturePermissionsAndServices() {
        val manifest = readManifest()

        val forbiddenTokens = listOf(
            "FOREGROUND_SERVICE",
            "SYSTEM_ALERT_WINDOW",
            "BIND_ACCESSIBILITY_SERVICE",
            "BIND_NOTIFICATION_LISTENER_SERVICE",
            "PaymentAccessibilityService",
            "PaymentNotificationService",
            "LedgerGuardService"
        )

        forbiddenTokens.forEach { token ->
            assertFalse(
                "Offline-first manifest must not reference $token",
                manifest.contains(token)
            )
        }

        assertFalse(
            "Accessibility service config must no longer ship",
            File("src/main/res/xml/payment_accessibility_config.xml").exists()
        )
    }

    @Test
    fun manifestRegistersQuickEntryTilesBootReceiverAndImageShareIntents() {
        val manifest = readManifest()

        val requiredTokens = listOf(
            "QuickEntryTileService",
            "ScreenshotOcrTileService",
            "QuickEntryBootReceiver",
            "android.intent.action.SEND",
            "android.intent.action.SEND_MULTIPLE",
            "image/*",
            "application/pdf"
        )

        requiredTokens.forEach { token ->
            assertTrue(
                "Manifest must declare $token",
                manifest.contains(token)
            )
        }
    }

    @Test
    fun manifestStripsTransitiveDatatransportTelemetryComponents() {
        val manifest = readManifest()
        val strippedComponents = listOf(
            "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery",
            "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService",
            "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver"
        )
        val elementRegex = Regex("""<(?:service|receiver)\b[^>]*/>""", setOf(RegexOption.DOT_MATCHES_ALL))
        val elements = elementRegex.findAll(manifest).map { it.value }.toList()

        strippedComponents.forEach { component ->
            val matchingElement = elements.firstOrNull { it.contains(component) }
            assertTrue(
                "Manifest must explicitly strip transitive datatransport component $component with tools:node=\"remove\"",
                matchingElement != null && matchingElement.contains("""tools:node="remove"""")
            )
        }
    }
}
