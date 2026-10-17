package heist.platform;

public interface PcmChannel {
    void setVolume(float volume);
    void pause();
    void resume();
    void stop();
    boolean isFinished();
}
