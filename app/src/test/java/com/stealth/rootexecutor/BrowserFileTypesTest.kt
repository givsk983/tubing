package com.stealth.rootexecutor

import org.junit.Assert.*
import org.junit.Test

class BrowserFileTypesTest {
    @Test fun recognizesApkAndNumberedDownloadNamesIncludingRequestedAkpSpelling() {
        listOf("app.apk", "APP.APK", "app.apk.1", "name 中文.APK.12", "app.akp.1").forEach {
            assertTrue(it, BrowserFileTypes.isApk(it))
        }
    }

    @Test fun rejectsDirectoriesAndNonApkSuffixes() {
        listOf("app.apk.zip", "app.apk.1.txt", "app.apks", "app.apk.-1", "app.apk.x", "apk", "photo.png").forEach {
            assertFalse(it, BrowserFileTypes.isApk(it))
        }
    }

    @Test fun recognizesImageSuffixesWithoutTreatingOtherFilesAsImages() {
        listOf("相片.JPG", "a.jpeg", "a.png", "a.webp", "a.gif", "a.bmp", "a.heic", "a.heif", "a.avif").forEach {
            assertTrue(it, BrowserFileTypes.isImage(it))
        }
        listOf("image.png.sh", "a.apk", "a.zip", "png").forEach { assertFalse(it, BrowserFileTypes.isImage(it)) }
    }
}
