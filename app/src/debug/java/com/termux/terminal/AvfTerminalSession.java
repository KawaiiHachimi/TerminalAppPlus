/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.termux.terminal;

/** Stream-backed session: never creates a host process or invokes the Termux PTY JNI. */
public final class AvfTerminalSession extends TerminalSession {
    public interface Writer { void write(byte[] bytes); }
    public interface ResizeListener { void onResize(int columns, int rows); }
    private final Writer writer;
    public ResizeListener resizeListener;
    public AvfTerminalSession(TerminalSessionClient client, Writer writer) {
        super("", "", new String[0], new String[0], 2000, client);
        this.writer = writer;
        initializeEmulator(80, 24, 0, 0);
    }
    @Override public void initializeEmulator(int cols, int rows, int cw, int ch) {
        mEmulator = new TerminalEmulator(this, cols, rows, cw, ch, 2000, mClient);
    }
    @Override public void updateSize(int cols, int rows, int cw, int ch) {
        mEmulator.resize(cols, rows, cw, ch);
        if (resizeListener != null) resizeListener.onResize(cols, rows);
    }
    @Override public void write(byte[] data, int offset, int count) {
        writer.write(java.util.Arrays.copyOfRange(data, offset, offset + count));
    }
    public void append(byte[] bytes) { mEmulator.append(bytes, bytes.length); notifyScreenUpdate(); }
    @Override public synchronized boolean isRunning() { return true; }
    @Override public void finishIfRunning() { /* Shared VM lifecycle belongs to VmController. */ }
    @Override public int getPid() { return -1; }
}
