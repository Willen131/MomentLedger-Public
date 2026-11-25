package com.momentledger.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManifestOfflineAuditTest {

    private fun readManifest(): String =
        File("src/main/AndroidManifest.xml").readText()

    private fun readScreenCaptureAccessibilityConfig(): String {
        val config = File("src/main/res/xml/screen_capture_accessibility.xml")
        assertTrue(
            "Dedicated @xml/screen_capture_accessibility config must ship",
            config.exists()
        )
        return config.readText()
    }

    private fun manifestComponent(manifest: String, tag: String, name: String): String {
        val pattern = Regex(
            """<$tag\b[^>]*android:name="${Regex.escape(name)}"[^>]*(?:/>|>.*?</$tag>)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val component = pattern.find(manifest)?.value
        assertTrue("Manifest must declare <$tag> $name", component != null)
        return component!!
    }

    private fun containsAttributeValue(xml: String, attribute: String, value: String): Boolean {
        val pattern = Regex.escape(attribute) + "\\s*=\\s*\"" + Regex.escape(value) + "\""
        return Regex(pattern).containsMatchIn(xml)
    }

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
            "BIND_NOTIFICATION_LISTENER_SERVICE",
            "WAKE_LOCK",
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
    fun floatingEntryIsTheOnlyForegroundServiceAndIsPrivate() {
        val manifest = readManifest()
        val foregroundPermissions = Regex("""android:name="(android\.permission\.FOREGROUND_SERVICE[^"]*)"""")
            .findAll(manifest).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"),
            foregroundPermissions.sorted()
        )
        assertEquals(1, Regex("""android:foregroundServiceType\s*=""").findAll(manifest).count())
        val service = manifestComponent(manifest, "service", ".quickentry.LedgerOverlayService")
        assertTrue(containsAttributeValue(service, "android:exported", "false"))
        assertTrue(containsAttributeValue(service, "android:foregroundServiceType", "specialUse"))
        assertTrue(service.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
        assertTrue(manifest.contains("""android:name="android.permission.SYSTEM_ALERT_WINDOW""""))
    }

    @Test
    fun manifestDeclaresSingleScreenshotAccessibilityServiceBoundToSystemPermission() {
        val manifest = readManifest()
        val systemPermission = "android.permission.BIND_ACCESSIBILITY_SERVICE"

        val permissionOccurrences = Regex(Regex.escape(systemPermission)).findAll(manifest).count()
        assertEquals(
            "Manifest must reference $systemPermission exactly once, on the screenshot service",
            1,
            permissionOccurrences
        )

        val service = manifestComponent(
            manifest,
            "service",
            ".quickentry.ScreenCaptureAccessibilityService"
        )
        assertTrue(
            "Screenshot accessibility service must be exported so the system can bind it",
            service.contains("""android:exported="true"""")
        )
        assertTrue(
            "Screenshot accessibility service must require the system BIND_ACCESSIBILITY_SERVICE permission",
            service.contains("""android:permission="$systemPermission"""")
        )
        assertTrue(
            "Screenshot accessibility service must reference the dedicated @xml/screen_capture_accessibility config",
            service.contains("""android:resource="@xml/screen_capture_accessibility"""")
        )
        assertTrue(
            "Dedicated config must be declared through the android.accessibilityservice meta-data",
            service.contains("""android:name="android.accessibilityservice"""")
        )

        val receiver = manifestComponent(
            manifest,
            "receiver",
            ".quickentry.ScreenCaptureReceiver"
        )
        assertTrue(
            "ScreenCaptureReceiver must not be exported",
            receiver.contains("""android:exported="false"""")
        )
    }

    @Test
    fun screenCaptureAccessibilityConfigTakesScreenshotsOnly() {
        val config = readScreenCaptureAccessibilityConfig()

        assertTrue(
            "Screenshot accessibility config must enable screenshot capture",
            containsAttributeValue(config, "android:canTakeScreenshot", "true")
        )
        assertTrue(
            "Screenshot accessibility config must not retrieve window content",
            containsAttributeValue(config, "android:canRetrieveWindowContent", "false")
        )

        val forbiddenAttributes = listOf(
            "android:accessibilityEventTypes",
            "android:canPerformGestures",
            "android:canRequestFilterKeyEvents"
        )
        forbiddenAttributes.forEach { attribute ->
            assertFalse(
                "Screenshot-only accessibility config must not declare $attribute",
                config.contains(attribute)
            )
        }
    }

    @Test
    fun manifestHasNoScreenProjectionServiceOrGalleryPermissions() {
        val manifest = readManifest()

        val forbiddenTokens = listOf(
            "MEDIA_PROJECTION",
            "mediaProjection",
            "MediaProjection",
            "READ_EXTERNAL_STORAGE",
            "WRITE_EXTERNAL_STORAGE",
            "READ_MEDIA_IMAGES",
            "READ_MEDIA_VIDEO",
            "READ_MEDIA_VISUAL_USER_SELECTED"
        )

        forbiddenTokens.forEach { token ->
            assertFalse(
                "Manifest must not reference screen projection or gallery access token $token",
                manifest.contains(token)
            )
        }
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
