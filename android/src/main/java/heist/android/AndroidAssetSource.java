package heist.android;

import android.content.res.AssetManager;

import java.io.IOException;
import java.io.InputStream;

import heist.platform.AssetSource;

/** The game files the build put into the APK's assets, under the folder the build fills. */
final class AndroidAssetSource implements AssetSource {
    /** Folder inside the APK's assets that the build fills (see android/build.gradle). */
    static final String ROOT = "heist/";

    private final AssetManager assets;

    AndroidAssetSource(AssetManager assets) { this.assets = assets; }

    public InputStream open(String name) throws IOException {
        return assets.open(ROOT + name, AssetManager.ACCESS_STREAMING);
    }

    public boolean exists(String name) {
        try {
            open(name).close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
