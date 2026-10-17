package heist.desktop.tools;

import java.io.File;
import java.io.IOException;

import heist.desktop.DesktopPlatform;
import heist.engine.Save;
import heist.game.Disarm;
import heist.platform.Host;

/** Sets the game up for a command line tool: the game files from a folder, and a throw-away save in a temporary folder. */
final class ToolSetup {
    private ToolSetup() {}

    static void init(File assets) throws IOException {
        System.setProperty("java.awt.headless", "true");
        File data = File.createTempFile("heist-tool", "");
        data.delete();
        data.mkdirs();
        data.deleteOnExit();
        Host.set(new DesktopPlatform(assets, data));
        heist.engine.Assets.reset();
        Save.load();
        Disarm.reload();
    }
}
