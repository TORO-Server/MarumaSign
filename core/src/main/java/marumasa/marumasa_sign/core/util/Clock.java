package marumasa.marumasa_sign.core.util;

/**
 * 経った時間を測るための時計 (ミリ秒)。パソコンの時計を合わせ直しても、戻ったり飛んだりしない。
 * 値そのものに意味はなく、2 つの値の差だけを使う (System.currentTimeMillis は時計を戻すと、
 * 使われていない看板がいつまでも消えなかったり、見えている看板がすべて消えたりする)。
 */
public final class Clock {

    private Clock() {
    }

    public static long millis() {
        return System.nanoTime() / 1_000_000L;
    }
}
