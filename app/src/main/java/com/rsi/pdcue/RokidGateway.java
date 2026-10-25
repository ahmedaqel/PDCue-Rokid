package com.rsi.pdcue;

/** Boundary for Rokid CXR-L integration. */
public interface RokidGateway {
    boolean isConnected();
    void sendDisplayCue(String title, String body);
    void requestPhoto();
    void startAudioSession();
    void stopAudioSession();
}
