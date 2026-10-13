package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.sign.SignImage;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GLContext;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 3D モデルの頂点を、配列にまとめて OpenGL に渡す。
 * 頂点が多いので、1 つずつ渡すと時間がかかる。
 */
final class ModelArrays {

    private static final int BYTES_PER_VERTEX = SignImage.Batch.STRIDE * 4;

    private static final class Buffers {
        final FloatBuffer vertices;
        final ByteBuffer colors;
        int revision = -1;

        Buffers(int vertexCount) {
            vertices = BufferUtils.createFloatBuffer(vertexCount * SignImage.Batch.STRIDE);
            colors = BufferUtils.createByteBuffer(vertexCount * 4);
        }
    }

    // まとまりごとのバッファ。まとまりが使われなくなれば、一緒に破棄される
    private static final Map<SignImage.Batch, Buffers> BUFFERS = new WeakHashMap<SignImage.Batch, Buffers>();

    private ModelArrays() {
    }

    static void draw(SignImage.Batch batch) {
        Buffers buffers = BUFFERS.get(batch);
        if (buffers == null) {
            buffers = new Buffers(batch.vertexCount);
            BUFFERS.put(batch, buffers);
        }
        if (buffers.revision != batch.revision) {
            // 内容が変わったときだけ詰め直す。
            // clear などは、新しい Java とは戻り値の型が違うので、どの Java でも同じ呼び出しになるよう Buffer として呼ぶ
            ((Buffer) buffers.vertices).clear();
            buffers.vertices.put(batch.vertices, 0, batch.vertexCount * SignImage.Batch.STRIDE);
            ((Buffer) buffers.colors).clear();
            final int[] colors = batch.colors;
            for (int i = 0; i < batch.vertexCount; i++) {
                final int color = colors[i];
                buffers.colors.put((byte) (color >> 16)).put((byte) (color >> 8)).put((byte) color).put((byte) (color >>> 24));
            }
            ((Buffer) buffers.colors).flip();
            buffers.revision = batch.revision;
        }

        // 変更した状態は glPopClientAttrib で元に戻す
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        if (GLContext.getCapabilities().OpenGL15) GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        if (GLContext.getCapabilities().OpenGL13) GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        // 位置とテクスチャ座標は、同じバッファに交互に入っている
        ((Buffer) buffers.vertices).position(0);
        GL11.glVertexPointer(3, BYTES_PER_VERTEX, buffers.vertices);
        ((Buffer) buffers.vertices).position(3);
        GL11.glTexCoordPointer(2, BYTES_PER_VERTEX, buffers.vertices);
        ((Buffer) buffers.colors).position(0);
        GL11.glColorPointer(4, true, 0, buffers.colors);
        GL11.glDrawArrays(GL11.GL_QUADS, 0, batch.vertexCount);
        GL11.glPopClientAttrib();
    }
}
