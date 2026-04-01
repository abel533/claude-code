package com.claudecode.console;

import java.io.PrintStream;

/**
 * 加载动画（Spinner）—— 对应 claude-code/src/components/Spinner.tsx。
 * <p>
 * 在等待 AI 响应时显示旋转动画。
 */
public class SpinnerAnimation {

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final int INTERVAL_MS = 80;

    private final PrintStream out;
    private volatile boolean running;
    private Thread thread;
    private String message = "Thinking";

    public SpinnerAnimation(PrintStream out) {
        this.out = out;
    }

    /** 启动 spinner */
    public void start(String message) {
        if (running) return;
        this.message = message;
        this.running = true;

        thread = Thread.ofVirtual().name("spinner").start(() -> {
            int idx = 0;
            while (running) {
                out.print(AnsiStyle.clearLine());
                out.print(AnsiStyle.CYAN + "  " + FRAMES[idx % FRAMES.length]
                        + " " + AnsiStyle.RESET + AnsiStyle.dim(this.message));
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

    /** 更新消息 */
    public void updateMessage(String newMessage) {
        this.message = newMessage;
    }

    public boolean isRunning() {
        return running;
    }
}
