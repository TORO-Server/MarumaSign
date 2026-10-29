/**
 * WebP の中身の展開。非可逆 (VP8、RFC 6386) は Vp8Decoder、可逆 (VP8L、RFC 9649 3 節) は LosslessDecoder、
 * 透明度のチャンク (ALPH) は AlphaDecoder が扱う。仕様書をもとに書いたもので、
 * 結果は参照デコーダ libwebp と同じになるようにしてある (RIFF のチャンクの読み分けは texture.WebpDecoder)。
 */
package marumasa.marumasa_sign.core.texture.webp;
