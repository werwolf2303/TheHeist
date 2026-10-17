package heist.desktop;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import heist.platform.AssetSource;

/** The game files in a folder on disk (what the IPA import produced). */
final class FileAssetSource implements AssetSource {
    private final File root;

    FileAssetSource(File root) { this.root = root; }

    File root() { return root; }

    public InputStream open(String name) throws IOException { return new FileInputStream(new File(root, name)); }

    public boolean exists(String name) { return new File(root, name).isFile(); }
}
