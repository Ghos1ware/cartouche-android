package com.limelight.binding.video;

/** The stream's numbers of the last two seconds, for Cartouche's stats panel (ui/StatsPanel). */
public class PerfStats {
    public int width;
    public int height;
    public float totalFps;
    public float receivedFps;
    public float renderedFps;
    public String decoder;
    public float lossPercent;
    public int rttMs;
    public int rttVarianceMs;
    /** The PC's own time to capture and encode a frame, or -1 when the server does not say. */
    public float hostLatencyMs = -1;
    public float decodeMs;
}
