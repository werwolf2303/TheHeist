package heist.platform;

import java.io.IOException;
import java.io.InputStream;

/** Read access to the extracted game files. */
public interface AssetSource {
    InputStream open(String name) throws IOException;

    boolean exists(String name);
}
