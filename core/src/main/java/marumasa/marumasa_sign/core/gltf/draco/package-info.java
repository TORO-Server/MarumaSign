/**
 * Draco で圧縮されたメッシュ (KHR_draco_mesh_compression) の展開。
 * Draco のビットストリームの仕様 (https://google.github.io/draco/spec/) をもとに書いた独自の実装で、
 * 細かい振る舞い (浮動小数点の計算の順番、古い版の形式) は Google の Draco のデコーダに合わせ、
 * 同じデータから 1 ビットも違わない結果になることを確かめてある。
 * 版 1.0〜2.2 のメッシュ (順番に並べる方式と EdgeBreaker) に対応する。点群 (KD 木) には対応しない。
 * 壊れたデータに対しては、配列の範囲・数の上限・処理の手間を確かめて IOException にする。
 */
package marumasa.marumasa_sign.core.gltf.draco;
