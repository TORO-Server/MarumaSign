package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.sign.SignImage;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;

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
        // 面の向き。影 Mod が光の当たり方を計算するのに使う
        final FloatBuffer normals;
        final ByteBuffer colors;
        int revision = -1;

        Buffers(int vertexCount) {
            vertices = BufferUtils.createFloatBuffer(vertexCount * SignImage.Batch.STRIDE);
            normals = BufferUtils.createFloatBuffer(vertexCount * 3);
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
            fillNormals(batch, buffers.normals);
            buffers.revision = batch.revision;
        }

        // 変更した状態は glPopClientAttrib で元に戻す
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        // 位置とテクスチャ座標は、同じバッファに交互に入っている
        ((Buffer) buffers.vertices).position(0);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, BYTES_PER_VERTEX, buffers.vertices);
        ((Buffer) buffers.vertices).position(3);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, BYTES_PER_VERTEX, buffers.vertices);
        ((Buffer) buffers.colors).position(0);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, 0, buffers.colors);
        GL11.glNormalPointer(GL11.GL_FLOAT, 0, buffers.normals);
        GL11.glDrawArrays(GL11.GL_QUADS, 0, batch.vertexCount);
        GL11.glPopClientAttrib();
    }

    // 四角形ごとに、面の向きを求めて入れる。三角形は 3 つ目の頂点を重ねた四角形なので、最初の 3 頂点から求める
    private static void fillNormals(SignImage.Batch batch, FloatBuffer normals) {
        final float[] mesh = batch.vertices;
        ((Buffer) normals).clear();
        for (int i = 0; i + 3 < batch.vertexCount; i += 4) {
            final int a = i * SignImage.Batch.STRIDE, b = a + SignImage.Batch.STRIDE, c = b + SignImage.Batch.STRIDE;
            final float ux = mesh[b] - mesh[a], uy = mesh[b + 1] - mesh[a + 1], uz = mesh[b + 2] - mesh[a + 2];
            final float vx = mesh[c] - mesh[a], vy = mesh[c + 1] - mesh[a + 1], vz = mesh[c + 2] - mesh[a + 2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            final float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length > 0) {
                nx /= length;
                ny /= length;
                nz /= length;
            } else {
                // 面積のない面は、上向きとして扱う
                ny = 1;
            }
            for (int k = 0; k < 4; k++) normals.put(nx).put(ny).put(nz);
        }
        ((Buffer) normals).flip();
    }
}
