package marumasa.marumasa_sign.core.gltf.draco;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Draco のメッシュ 1 つを展開する作業。
 * 見出し → (メタデータ) → 面のつながり → 属性の順に読む。
 * 版は 1.0〜2.2 に対応する (glTF の KHR_draco_mesh_compression は 2.2)。
 */
final class MeshDecoding {

    private static final int HEADER_SIZE = 11;
    private static final int METADATA_FLAG = 0x8000;
    private static final int SEQUENTIAL = 0;
    private static final int EDGEBREAKER = 1;
    // 属性の種類の数 (位置・法線・色・UV・汎用と、変換用の拡張の種類)
    private static final int ATTRIBUTE_TYPES = 9;

    final ByteReader in;
    final int version;
    final Budget budget = new Budget();

    int[] faces = new int[0];
    int numPoints;
    private final List<PointAttribute> attributes = new ArrayList<>();
    private final List<AttributeDecoder> attributeDecoders = new ArrayList<>();

    private MeshDecoding(ByteReader in) {
        this.in = in;
        this.version = in.version;
    }

    static DracoMesh decode(byte[] data) throws IOException {
        if (data.length < HEADER_SIZE) throw new IOException("Draco data is too short");
        if (data[0] != 'D' || data[1] != 'R' || data[2] != 'A' || data[3] != 'C' || data[4] != 'O') {
            throw new IOException("Not Draco data");
        }
        final int major = data[5] & 0xff;
        final int minor = data[6] & 0xff;
        final int type = data[7] & 0xff;
        final int method = data[8] & 0xff;
        final int flags = (data[9] & 0xff) | (data[10] & 0xff) << 8;
        if (major < 1 || major > 2 || (major == 2 && minor > 2)) {
            throw new IOException("Unsupported Draco version " + major + "." + minor);
        }
        if (type != 1) throw new IOException("Decoded geometry is not a Draco triangular mesh");
        if (method != SEQUENTIAL && method != EDGEBREAKER) throw new IOException("Unsupported Draco mesh method: " + method);
        final MeshDecoding d = new MeshDecoding(new ByteReader(data, HEADER_SIZE, data.length, major << 8 | minor));
        if (d.version >= ByteReader.V13 && (flags & METADATA_FLAG) != 0) d.skipMetadata();
        EdgebreakerDecoder edgebreaker = null;
        if (method == EDGEBREAKER) {
            edgebreaker = new EdgebreakerDecoder(d);
            edgebreaker.decodeConnectivity();
        } else {
            d.decodeSequentialConnectivity();
        }
        d.decodeAttributes(edgebreaker);
        return new DracoMesh(d.faces, d.numPoints, d.attributes);
    }

    void setFaces(int[] faces, int numPoints) throws IOException {
        if (numPoints < 0 || numPoints > Budget.MAX_POINTS) throw new IOException("Too many Draco points");
        this.faces = faces;
        this.numPoints = numPoints;
    }

    // ---------- メタデータ (使わないので読み飛ばす) ----------

    private void skipMetadata() throws IOException {
        final long attributeMetadata = in.varU32();
        for (long i = 0; i < attributeMetadata; i++) {
            in.varU32(); // 属性の番号
            skipMetadataTree();
        }
        skipMetadataTree();
    }

    // 入れ子のメタデータを、残りの数だけを数えながら読み飛ばす (再帰しない)
    private void skipMetadataTree() throws IOException {
        long pending = 1;
        boolean first = true;
        while (pending > 0) {
            pending--;
            // 入れ子のメタデータには名前が付いている
            if (!first) skipName();
            first = false;
            final long entries = in.varU32();
            for (long i = 0; i < entries; i++) {
                skipName();
                final long size = in.varU32();
                if (size == 0) throw new IOException("Invalid Draco metadata entry");
                in.skip(size);
            }
            final long children = in.varU32();
            if (children > in.remaining()) throw new IOException("Too many Draco metadata entries");
            pending += children;
        }
    }

    private void skipName() throws IOException {
        in.skip(in.u8());
    }

    // ---------- 順番に並んだ面 ----------

    private void decodeSequentialConnectivity() throws IOException {
        final long numFaces = in.count32Before22();
        final long points = in.count32Before22();
        if (numFaces > in.remaining() / 3 || numFaces > Budget.MAX_FACES) throw new IOException("Too many Draco faces");
        // 面から参照されない点は使い道がないので、角の数より大きく超えるものははじく
        if (points > Budget.MAX_POINTS || points > 3 * numFaces + 65536) throw new IOException("Too many Draco points");
        final int n = (int) numFaces * 3;
        budget.alloc(n);
        final int[] indices = new int[n];
        final int connectivity = in.u8();
        if (connectivity == 0) {
            // 前の番号との差を、符号を最下位ビットに入れて rANS で書いてある
            Rans.decodeSymbols(in, n, 1, indices);
            long last = 0;
            for (int i = 0; i < n; i++) {
                final long encoded = indices[i] & 0xffffffffL;
                long diff = encoded >>> 1;
                if ((encoded & 1) != 0) {
                    if (diff > last) throw new IOException("Invalid Draco index");
                    diff = -diff;
                } else if (diff > Integer.MAX_VALUE - last) {
                    throw new IOException("Invalid Draco index");
                }
                final long value = last + diff;
                if (value < 0 || value >= points) throw new IOException("Draco index is out of range");
                indices[i] = (int) value;
                last = value;
            }
        } else {
            for (int i = 0; i < n; i++) {
                final long value;
                if (points < 256) {
                    value = in.u8();
                } else if (points < (1 << 16)) {
                    value = in.u16();
                } else if (points < (1 << 21) && version >= ByteReader.V22) {
                    value = in.varU32();
                } else {
                    value = in.u32();
                }
                if (value >= points) throw new IOException("Draco index is out of range");
                indices[i] = (int) value;
            }
        }
        setFaces(indices, (int) points);
    }

    // ---------- 属性 ----------

    private void decodeAttributes(EdgebreakerDecoder edgebreaker) throws IOException {
        final int numGroups = in.u8();
        final List<AttributeGroup> groups = new ArrayList<>();
        for (int i = 0; i < numGroups; i++) {
            final AttributeGroup group = new AttributeGroup();
            if (edgebreaker != null) edgebreaker.readGroupHeader(group, i);
            groups.add(group);
        }
        for (AttributeGroup group : groups) readGroupAttributes(group);
        for (AttributeGroup group : groups) group.decode(this, in);
    }

    // まとまりに含まれる属性の説明と、値の書き方を読む
    private void readGroupAttributes(AttributeGroup group) throws IOException {
        final long count = in.count32Before20();
        if (count == 0 || count > 5L * in.remaining()) throw new IOException("Invalid Draco attribute count");
        final int first = attributes.size();
        for (long i = 0; i < count; i++) {
            final int type = in.u8();
            final int dataType = in.u8();
            final int components = in.u8();
            final int normalized = in.u8();
            if (type >= ATTRIBUTE_TYPES) throw new IOException("Invalid Draco attribute type: " + type);
            if (dataType == DataType.INVALID || dataType >= DataType.TYPES_COUNT) {
                throw new IOException("Invalid Draco data type: " + dataType);
            }
            if (components == 0) throw new IOException("Draco attribute has no components");
            final long uniqueId = version < ByteReader.V13 ? in.u16() : in.varU32();
            attributes.add(new PointAttribute(type, dataType, components, normalized > 0, (int) uniqueId));
            attributeDecoders.add(null);
        }
        for (int i = first; i < attributes.size(); i++) {
            final int kind = in.u8();
            if (kind > AttributeDecoder.NORMALS) throw new IOException("Unknown Draco attribute encoding: " + kind);
            final AttributeDecoder decoder = new AttributeDecoder(this, group, kind, attributes.get(i));
            attributeDecoders.set(i, decoder);
            group.decoders.add(decoder);
        }
    }

    /**
     * 予測に使う位置の属性を探す。位置がまだ読まれていなければ誤り。
     */
    Prediction.Parent positionParent(AttributeDecoder requester) throws IOException {
        int id = -1;
        for (int i = 0; i < attributes.size(); i++) {
            if (attributes.get(i).getAttributeType() == PointAttribute.POSITION) {
                id = i;
                break;
            }
        }
        if (id < 0) throw new IOException("Draco prediction needs a position attribute");
        final PointAttribute position = attributes.get(id);
        if (version < ByteReader.V20) {
            // 古い形式では展開後の値を使う
            if (position.getComponentsCount() != 3) throw new IOException("Draco position must have 3 components");
            return new Prediction.Parent(position, null, 0);
        }
        final AttributeDecoder decoder = attributeDecoders.get(id);
        if (decoder == null || decoder.portable == null || decoder.kind == AttributeDecoder.NORMALS
                || position.getComponentsCount() != 3) {
            throw new IOException("Draco position is not available for prediction");
        }
        return new Prediction.Parent(position, decoder.portable, decoder.portableEntries);
    }
}
