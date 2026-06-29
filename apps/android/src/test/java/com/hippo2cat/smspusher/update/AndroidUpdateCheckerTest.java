package com.hippo2cat.smspusher.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Optional;

public final class AndroidUpdateCheckerTest {
    @Test
    public void selectsNewerStableReleaseWithExactApkAsset() {
        AndroidUpdateChecker.ReleaseInfo release = release(
            "v1.0.3",
            false,
            false,
            new AndroidUpdateChecker.ReleaseAsset("SmsPusher-1.0.3.apk", "https://example.com/SmsPusher-1.0.3.apk"),
            new AndroidUpdateChecker.ReleaseAsset("SmsPusher-1.0.3.dmg", "https://example.com/SmsPusher-1.0.3.dmg")
        );

        Optional<AndroidUpdateChecker.UpdateCandidate> candidate =
            AndroidUpdateChecker.selectUpdateCandidate("1.0.2", null, release);

        assertTrue(candidate.isPresent());
        assertEquals("1.0.3", candidate.get().versionName);
        assertEquals("SmsPusher-1.0.3.apk", candidate.get().assetName);
        assertEquals("https://example.com/SmsPusher-1.0.3.apk", candidate.get().downloadUrl);
    }

    @Test
    public void skipsSameOlderDraftPrereleaseAndAlreadyPromptedVersions() {
        assertFalse(AndroidUpdateChecker.selectUpdateCandidate(
            "1.0.2",
            null,
            release("v1.0.2", false, false, apk("1.0.2"))
        ).isPresent());
        assertFalse(AndroidUpdateChecker.selectUpdateCandidate(
            "1.0.2",
            null,
            release("v1.0.1", false, false, apk("1.0.1"))
        ).isPresent());
        assertFalse(AndroidUpdateChecker.selectUpdateCandidate(
            "1.0.2",
            null,
            release("v1.0.3", true, false, apk("1.0.3"))
        ).isPresent());
        assertFalse(AndroidUpdateChecker.selectUpdateCandidate(
            "1.0.2",
            null,
            release("v1.0.3-beta.1", false, true, new AndroidUpdateChecker.ReleaseAsset(
                "SmsPusher-1.0.3-beta.1.apk",
                "https://example.com/SmsPusher-1.0.3-beta.1.apk"
            ))
        ).isPresent());
        assertFalse(AndroidUpdateChecker.selectUpdateCandidate(
            "1.0.2",
            "1.0.3",
            release("v1.0.3", false, false, apk("1.0.3"))
        ).isPresent());
    }

    @Test
    public void fallsBackToVersionedApkWhenExactAssetNameIsMissing() {
        AndroidUpdateChecker.ReleaseInfo release = release(
            "v1.0.3",
            false,
            false,
            new AndroidUpdateChecker.ReleaseAsset("SmsPusher-universal-1.0.3.apk", "https://example.com/universal.apk"),
            new AndroidUpdateChecker.ReleaseAsset("SmsPusher-1.0.3.dmg", "https://example.com/SmsPusher-1.0.3.dmg")
        );

        Optional<AndroidUpdateChecker.UpdateCandidate> candidate =
            AndroidUpdateChecker.selectUpdateCandidate("1.0.2", null, release);

        assertTrue(candidate.isPresent());
        assertEquals("1.0.3", candidate.get().versionName);
        assertEquals("SmsPusher-universal-1.0.3.apk", candidate.get().assetName);
        assertEquals("https://example.com/universal.apk", candidate.get().downloadUrl);
    }

    @Test
    public void parsesGitHubReleaseJson() throws Exception {
        String json = "{"
            + "\"tag_name\":\"v1.0.3\","
            + "\"draft\":false,"
            + "\"prerelease\":false,"
            + "\"assets\":["
            + "{\"name\":\"SmsPusher-1.0.3.apk\",\"browser_download_url\":\"https://example.com/app.apk\"},"
            + "{\"name\":\"notes.txt\",\"browser_download_url\":\"https://example.com/notes.txt\"}"
            + "]"
            + "}";

        AndroidUpdateChecker.ReleaseInfo release = AndroidUpdateChecker.parseRelease(json);

        assertEquals("v1.0.3", release.tagName);
        assertFalse(release.draft);
        assertFalse(release.prerelease);
        assertEquals(2, release.assets.size());
        assertEquals("SmsPusher-1.0.3.apk", release.assets.get(0).name);
        assertEquals("https://example.com/app.apk", release.assets.get(0).browserDownloadUrl);
    }

    @Test
    public void parsesPagesManifestAndSelectsAndroidApkCandidate() throws Exception {
        String json = "{"
            + "\"version\":\"1.0.3\","
            + "\"buildNumber\":12,"
            + "\"channel\":\"stable\","
            + "\"releaseNotesUrl\":\"https://github.com/hippo2cat/AndroidSmsPushToMacos/releases/tag/v1.0.3\","
            + "\"platforms\":{"
            + "\"macos\":{\"assetName\":\"SmsPusher-1.0.3.dmg\",\"downloadUrl\":\"https://example.com/SmsPusher-1.0.3.dmg\"},"
            + "\"android\":{\"assetName\":\"SmsPusher-1.0.3.apk\",\"downloadUrl\":\"https://example.com/SmsPusher-1.0.3.apk\"}"
            + "}"
            + "}";

        AndroidUpdateChecker.UpdateManifest manifest = AndroidUpdateChecker.parseManifest(json);
        Optional<AndroidUpdateChecker.UpdateCandidate> candidate =
            AndroidUpdateChecker.selectUpdateCandidate("1.0.2", null, manifest);

        assertTrue(candidate.isPresent());
        assertEquals("1.0.3", candidate.get().versionName);
        assertEquals("SmsPusher-1.0.3.apk", candidate.get().assetName);
        assertEquals("https://example.com/SmsPusher-1.0.3.apk", candidate.get().downloadUrl);
    }

    private static AndroidUpdateChecker.ReleaseAsset apk(String versionName) {
        return new AndroidUpdateChecker.ReleaseAsset(
            "SmsPusher-" + versionName + ".apk",
            "https://example.com/SmsPusher-" + versionName + ".apk"
        );
    }

    private static AndroidUpdateChecker.ReleaseInfo release(
        String tagName,
        boolean draft,
        boolean prerelease,
        AndroidUpdateChecker.ReleaseAsset... assets
    ) {
        return new AndroidUpdateChecker.ReleaseInfo(tagName, draft, prerelease, List.of(assets));
    }
}
