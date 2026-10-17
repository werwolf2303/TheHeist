package heist.android;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import android.os.Build;

import heist.debug.DebugMenu;
import heist.engine.Assets;
import heist.engine.Audio;
import heist.engine.Game;
import heist.engine.Save;
import heist.engine.Key;
import heist.game.HintCall;
import heist.game.HubScene;
import heist.game.Nav;
import heist.platform.Host;

/** Portrait 320x480 letterboxed. Game files bundled into the APK's assets at build time. */
public class MainActivity extends Activity {
    /** Survives activity recreation (fold, low-memory kill, theme change). */
    private static Game sGame;
    private Game game;
    private GameView view;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestCameraPermission();
        Host.set(new AndroidPlatform(this));
        Save.load();
        if (!Assets.isInstalled()) {
            TextView t = new TextView(this);
            t.setBackgroundColor(0xFF000000);
            t.setTextColor(0xFFFFFFFF);
            t.setTextSize(18);
            t.setGravity(Gravity.CENTER);
            t.setText("This build contains no game files.");
            setContentView(t);
            return;
        }
        startGame();
    }

    /** Requested at launch so it's settled by the time the prize call needs it. Denied = no self-view, no error. */
    private void requestCameraPermission() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.CAMERA }, 1);
        }
    }

    private void startGame() {
        if (sGame == null) {
            sGame = new Game();
            Nav.init(sGame);
            HubScene hub = new HubScene();
            Nav.setHub(hub);
            sGame.setScene(hub);
            if (BuildConfig.DEBUG) sGame.setOverlay(new DebugMenu());
        }
        game = sGame;
        view = new GameView(this, game, BuildConfig.DEBUG);
        setContentView(view);
        hideSystemBars();
        view.resumeLoop();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 19) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } else {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && view != null) hideSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (view != null) {
            Audio.resume();
            view.resumeLoop();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (view != null) {
            view.pauseLoop();
            Audio.suspend();
            HintCall.onExit();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (game != null) game.key(Key.BACK);
        else super.onBackPressed();
    }
}
