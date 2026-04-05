package com.claudecode.console;

import java.io.PrintStream;

/**
 * 加载动画（Spinner）—— 对应 claude-code/src/components/Spinner.tsx。
 * <p>
 * 在等待 AI 响应时显示旋转动画。
 * 增强功能：多种动画样式、进度追踪、耗时计时。
 */
public class SpinnerAnimation {

    /** 标准 braille spinner */
    private static final String[] BRAILLE_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    /** 简约点动画 */
    private static final String[] DOT_FRAMES = {"⠋", "⠙", "⠚", "⠒", "⠂", "⠂", "⠒", "⠖", "⠦", "⠖", "⠒", "⠂", "⠂", "⠒", "⠚", "⠙"};
    /** 箭头动画 */
    private static final String[] ARROW_FRAMES = {"▹▹▹▹▹", "▸▹▹▹▹", "▹▸▹▹▹", "▹▹▸▹▹", "▹▹▹▸▹", "▹▹▹▹▸"};

    private static final int INTERVAL_MS = 80;

    private final PrintStream out;
    private volatile boolean running;
    private Thread thread;
    private String message = "Thinking";
    private long startTimeMs;
    private String[] frames = BRAILLE_FRAMES;

    public SpinnerAnimation(PrintStream out) {
        this.out = out;
    }

    /** 设置动画样式 */
    public SpinnerAnimation withStyle(Style style) {
        this.frames = switch (style) {
            case BRAILLE -> BRAILLE_FRAMES;
            case DOT -> DOT_FRAMES;
            case ARROW -> ARROW_FRAMES;
        };
        return this;
    }

    /** 启动 spinner */
    public void start(String message) {
        if (running) return;
        this.message = message;
        this.running = true;
        this.startTimeMs = System.currentTimeMillis();

        thread = Thread.ofVirtual().name("spinner").start(() -> {
            int idx = 0;
            while (running) {
                long elapsed = System.currentTimeMillis() - startTimeMs;
                String timeStr = elapsed > 2000 ? " " + formatElapsed(elapsed) : "";

                out.print(AnsiStyle.clearLine());
                out.print(AnsiStyle.CYAN + "  " + frames[idx % frames.length]
                        + " " + AnsiStyle.RESET + AnsiStyle.dim(this.message + timeStr));
                out.flush();
                idx++;
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // 清除 spinner 行
            out.print(AnsiStyle.clearLine());
            out.flush();
        });
    }

    /** 停止 spinner */
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 停止 spinner 并返回耗时 (ms) */
    public long stopAndGetElapsed() {
        long elapsed = System.currentTimeMillis() - startTimeMs;
        stop();
        return elapsed;
    }

    /** 更新消息 */
    public void updateMessage(String newMessage) {
        this.message = newMessage;
    }

    public boolean isRunning() {
        return running;
    }

    /** 获取已经过的时间 (ms) */
    public long getElapsedMs() {
        return System.currentTimeMillis() - startTimeMs;
    }

    private String formatElapsed(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format("%.1fs", ms / 1000.0);
        return String.format("%dm%ds", ms / 60_000, (ms % 60_000) / 1000);
    }

    /** 动画样式 */
    public enum Style {
        BRAILLE, DOT, ARROW
    }
}
