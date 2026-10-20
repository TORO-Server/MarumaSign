package marumasa.marumasa_sign.core;

import java.io.File;

/**
 * Minecraft のバージョンやローダーごとに実装される機能。
 * core はこのインターフェース経由でのみ Minecraft に触れる。
 */
public interface Platform {

    // 実行中の Minecraft のバージョン (例: "1.12.2")
    String minecraftVersion();

    // ローダーの名前 (例: "forge")
    String loaderName();

    // ゲームディレクトリ (.minecraft)
    File gameDirectory();

    // 選択中の言語コード (例: "ja_jp")
    String languageCode();

    // クライアントのメインスレッドで実行する
    void execute(Runnable task);

    // チャット欄にクライアント側だけのメッセージを表示する (§ による色指定を含む)
    void showMessage(String message);

    // ワールドに入っているかどうか
    boolean isInWorld();

    // 看板 1 行に書き込める最大文字数 (サーバー側の制限)
    int maxSignLineLength();

    // ARGB のピクセル配列からテクスチャを作る。返り値は実装ごとのハンドル
    Object createTexture(int width, int height, int[] argb);

    // 同じ大きさのテクスチャの内容を書き換える
    void updateTexture(Object texture, int width, int height, int[] argb);

    // テクスチャを削除する
    void releaseTexture(Object texture);

    // MarumaSign の画面を開く / 閉じる
    void openUi();

    void closeUi();

    // 画面へのファイルのドラッグ&ドロップに対応しているかどうか
    boolean supportsFileDrop();

    String getClipboard();

    void setClipboard(String text);

    /**
     * 照準 (画面中央) の視線。PDF のページ送りの操作に使う。ワールドに入っていなければ null。
     * {目の位置 x, y, z, 視線の向き (長さ 1) x, y, z, 照準が合っているブロックまでの距離 (合っていなければ -1)}
     */
    default double[] crosshair() {
        return null;
    }

    // ---------- 動作確認用 ----------

    // 視線の上下の向きを変える (度。下向きが正)
    void selfTestLook(float pitch);

    // 視線の向きを変える (度。yaw は南が 0 で西が 90、pitch は下向きが正)
    default void selfTestLook(float yaw, float pitch) {
        selfTestLook(pitch);
    }

    // 看板を手に持ち、見ているブロックに使用する
    void selfTestUseItem();

    // タイトル画面が出ていれば、サーバーへの接続を始めて true を返す。まだ始められないか、対応していなければ false。
    // 起動と同時に接続すると入れないバージョンがあるので、そのバージョンの確認では、起動が済んでからここで接続する
    default boolean selfTestJoin(String host, int port) {
        return false;
    }

    // ゲームを終了する
    void quit();
}
