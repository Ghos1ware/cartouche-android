package com.limelight.binding.video;

public interface PerfOverlayListener {
    void onPerfUpdate(final String text);

    /** The same numbers, as numbers (Cartouche's stats panel). */
    default void onPerfStats(PerfStats stats) {
    }
}
