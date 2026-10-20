package marumasa.marumasa_sign.core.ui;

import marumasa.marumasa_sign.core.Config;
import marumasa.marumasa_sign.core.Lang;
import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.gltf.GltfLoader;
import marumasa.marumasa_sign.core.gltf.GltfPacker;
import marumasa.marumasa_sign.core.gltf.GltfPreview;
import marumasa.marumasa_sign.core.gltf.GltfScene;
import marumasa.marumasa_sign.core.image.DecodedImage;
import marumasa.marumasa_sign.core.image.ImageLoader;
import marumasa.marumasa_sign.core.media.MediaTools;
import marumasa.marumasa_sign.core.net.Http;
import marumasa.marumasa_sign.core.pdf.PdfFile;
import marumasa.marumasa_sign.core.structure.StructureLoader;
import marumasa.marumasa_sign.core.sign.SignSpec;
import marumasa.marumasa_sign.core.splat.SplatLoader;
import marumasa.marumasa_sign.core.splat.SplatScene;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 画像と表示方法を決めて、看板に書き込むための画面。
 */
public final class MainPage extends Page {

    // 入力が止まってからプレビューを読み込むまでの時間 (tick)
    private static final int PREVIEW_DELAY = 8;

    private final TextField source;
    private final Button browse;
    private final NumberField width, height;
    private final NumberField posX, posY, posZ;
    private final NumberField rotX, rotY, rotZ;
    private final NumberField threads;
    private final NumberField volume;
    private final Button clearCache;
    private final ImageView preview;
    private final Label previewInfo;
    private final Label status;
    private final Button place;

    private boolean busy;
    private int previewCountdown = -1;
    private int previewGeneration;

    // アップロード済みのファイル (パスと更新日時と大きさ -> URL)
    private final Map<String, String> uploaded = new HashMap<String, String>();

    MainPage(UiRoot root) {
        super(root);

        add(new GroupBox(Lang.get("ui.group.image")).bounds(12, 34, 344, 78));
        add(new Label(Lang.get("ui.source")).bounds(22, 50, 324, 14));
        source = add(new TextField().onChange(this::sourceChanged));
        source.bounds(22, 66, 244, 21);
        browse = add(new Button(Lang.get("ui.browse"), this::openBrowser));
        browse.bounds(272, 65, 74, 23);
        if (root.platform.supportsFileDrop()) {
            add(new Label(Lang.get("ui.drop_hint")).bounds(22, 91, 324, 14)).enabled = false;
        }

        add(new GroupBox(Lang.get("ui.group.size")).bounds(12, 116, 110, 106));
        width = numberRow(Lang.get("ui.width"), 22, 134, 36, 56, 1f, 0.1f);
        height = numberRow(Lang.get("ui.height"), 22, 162, 36, 56, 1f, 0.1f);

        add(new GroupBox(Lang.get("ui.group.position")).bounds(128, 116, 110, 106));
        posX = numberRow("X", 138, 134, 18, 74, 0f, 0.1f);
        posY = numberRow("Y", 138, 162, 18, 74, 0f, 0.1f);
        posZ = numberRow("Z", 138, 190, 18, 74, 0f, 0.1f);

        add(new GroupBox(Lang.get("ui.group.rotation")).bounds(244, 116, 112, 106));
        rotX = numberRow("X", 254, 134, 18, 76, 0f, 5f);
        rotY = numberRow("Y", 254, 162, 18, 76, 0f, 5f);
        rotZ = numberRow("Z", 254, 190, 18, 76, 0f, 5f);

        final Config config = MarumaSign.config();
        add(new GroupBox(Lang.get("ui.group.settings")).bounds(12, 226, 344, 80));
        add(new Label(Lang.get("ui.threads")).bounds(22, 246, 136, 21));
        threads = add(new NumberField(config.getMaxThreads(), Config.MIN_THREADS, Config.MAX_THREADS, 1f, true));
        threads.bounds(160, 246, 48, 21);
        threads.onChange(() -> config.setMaxThreads(Math.round(threads.getValue())));
        clearCache = add(new Button(Lang.get("ui.clear_cache"), this::clearCache));
        clearCache.bounds(216, 245, 132, 23);
        add(new Label(Lang.get("ui.volume")).bounds(22, 274, 136, 21));
        volume = add(new NumberField(config.getVolume(), 0, 100, 5f, true));
        volume.bounds(160, 274, 48, 21);
        volume.onChange(() -> config.setVolume(Math.round(volume.getValue())));

        add(new GroupBox(Lang.get("ui.group.preview")).bounds(364, 34, 172, 272));
        preview = add(new ImageView());
        preview.bounds(372, 52, 156, 228);
        preview.setPlaceholder(Lang.get("ui.preview.none"));
        previewInfo = add(new Label(""));
        previewInfo.bounds(372, 284, 156, 14);

        status = add(new Label(Lang.get("ui.status.ready")));
        status.bounds(12, 318, 524, 16);

        place = add(new Button(Lang.get("ui.place"), this::place));
        place.bounds(318, 344, 128, 24);
        final Button cancel = add(new Button(Lang.get("ui.cancel"), root::close));
        cancel.bounds(452, 344, 84, 24);

        defaultButton = place;
        cancelButton = cancel;
    }

    private NumberField numberRow(String label, int x, int y, int labelWidth, int fieldWidth, float value, float step) {
        add(new Label(label).bounds(x, y, labelWidth, 21));
        final NumberField field = add(new NumberField(value, -1000f, 1000f, step, false));
        field.bounds(x + labelWidth + 4, y, fieldWidth, 21);
        return field;
    }

    @Override
    public String title() {
        return "MarumaSign";
    }

    @Override
    public void shown() {
        if (focus == null) setFocus(source);
    }

    public void setSource(String value) {
        source.setText(value);
        sourceChanged();
    }

    public String getSource() {
        return source.getText().trim();
    }

    public boolean isBusy() {
        return busy;
    }

    public String statusText() {
        return statusText;
    }

    private String statusText = "";

    private void setStatus(String text, boolean error) {
        statusText = text;
        status.setText(text, error ? Theme.ERROR_TEXT : Theme.TEXT);
    }

    private void setBusy(boolean value) {
        busy = value;
        place.enabled = !value;
        browse.enabled = !value;
        source.enabled = !value;
        repaint();
    }

    @Override
    public void filesDropped(List<File> files) {
        if (busy) return;
        setSource(files.get(0).getAbsolutePath());
    }

    private void openBrowser() {
        root.showPage(new FileBrowserPage(root, this));
    }

    private void clearCache() {
        if (MarumaSign.signs().isBusy()) {
            setStatus(Lang.get("ui.status.cache_busy"), true);
            return;
        }
        MarumaSign.signs().clearCache();
        setStatus(Lang.get("ui.status.cache_cleared"), false);
    }

    // ---------- プレビュー ----------

    private void sourceChanged() {
        previewGeneration++;
        previewCountdown = PREVIEW_DELAY;
    }

    @Override
    public void tick() {
        if (previewCountdown < 0) return;
        if (previewCountdown-- > 0) return;
        loadPreview();
    }

    // プレビューの画像の一辺の長さ (3D モデルの場合)
    private static final int MODEL_PREVIEW_SIZE = 256;
    // PDF のプレビューの、長い辺の大きさ
    private static final int PDF_PREVIEW_SIZE = 384;

    // ファイルの先頭のバイト列を読む
    private static byte[] readHead(File file) throws IOException {
        if (!file.isFile()) throw new IOException(Lang.get("ui.status.file_not_found", file.getPath()));
        final byte[] head = new byte[512];
        final int length;
        final InputStream input = new FileInputStream(file);
        try {
            length = Math.max(0, input.read(head));
        } finally {
            input.close();
        }
        return java.util.Arrays.copyOf(head, length);
    }

    // 画像ファイルなら内容を返す。画像でなければ null
    private static byte[] readImageFile(File file) throws IOException {
        final byte[] head = readHead(file);
        if (!ImageLoader.isSupportedImage(head, head.length)) return null;
        if (file.length() > Http.MAX_SIZE) throw new IOException(Lang.get("ui.status.file_too_large", 10));
        return Files.readAllBytes(file.toPath());
    }

    // 3D モデルのファイルかどうか
    private static boolean isModelFile(File file) throws IOException {
        final byte[] head = readHead(file);
        return ImageLoader.isModel(head, head.length);
    }

    // 構造物のファイルかどうか
    private static boolean isStructureFile(File file) throws IOException {
        final byte[] head = readHead(file);
        return ImageLoader.isStructure(head, head.length);
    }

    // Gaussian Splatting のファイルかどうか
    private static boolean isSplatFile(File file) throws IOException {
        final byte[] head = readHead(file);
        return SplatLoader.isSplat(head, head.length, file.getName());
    }

    // PDF ファイルかどうか
    private static boolean isPdfFile(File file) throws IOException {
        final byte[] head = readHead(file);
        return PdfFile.isPdf(head, head.length);
    }

    // PDF の最初のページを、プレビューの大きさの画像にする
    private static BufferedImage pdfPreview(PdfFile pdf) throws IOException {
        try {
            return toBufferedImage(pdf.render(0, PDF_PREVIEW_SIZE));
        } finally {
            pdf.close();
        }
    }

    private static BufferedImage toBufferedImage(DecodedImage image) {
        final BufferedImage result = new BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0, 0, image.width, image.height, image.frames[0], 0, image.width);
        return result;
    }

    private void loadPreview() {
        final String text = getSource();
        final int generation = previewGeneration;
        if (text.isEmpty()) {
            preview.setImage(null);
            preview.setPlaceholder(Lang.get("ui.preview.none"));
            previewInfo.setText("");
            return;
        }
        preview.setImage(null);
        preview.setPlaceholder(Lang.get("ui.preview.loading"));
        previewInfo.setText("");

        worker("MarumaSign-Preview", () -> {
            BufferedImage image = null;
            String info = "";
            try {
                final boolean[] isImage = new boolean[1];
                final boolean[] isModel = new boolean[1];
                final boolean[] isSplat = new boolean[1];
                final boolean[] isPdf = new boolean[1];
                GltfScene model = null;
                SplatScene splat = null;
                PdfFile pdf = null;
                final byte[] bytes;
                if (Http.isHttpUrl(text)) {
                    final String[] resolvedName = new String[]{text};
                    bytes = Http.download(text, new Http.ContentCheck() {
                        @Override
                        public int maxBytes(byte[] head, int length, String name) {
                            resolvedName[0] = name;
                            if (ImageLoader.isSupportedImage(head, length)) {
                                isImage[0] = true;
                                return Http.MAX_SIZE;
                            }
                            if (PdfFile.isPdf(head, length)) {
                                isPdf[0] = true;
                                return PdfFile.MAX_SIZE;
                            }
                            if (ImageLoader.isModel(head, length)) {
                                isModel[0] = true;
                                return ImageLoader.MAX_MODEL_SIZE;
                            }
                            if (SplatLoader.isSplat(head, length, name)) {
                                isSplat[0] = true;
                                return ImageLoader.MAX_SPLAT_SIZE;
                            }
                            return 0;
                        }
                    });
                    if (bytes != null && isPdf[0]) pdf = PdfFile.open(bytes);
                    else if (bytes != null && isModel[0]) model = ImageLoader.model(resolvedName[0], bytes);
                    else if (bytes != null && isSplat[0]) splat = ImageLoader.splat(resolvedName[0], bytes);
                } else {
                    final File file = new File(text);
                    bytes = readImageFile(file);
                    if (bytes == null) {
                        if (isPdfFile(file)) pdf = PdfFile.open(file);
                        else if (isModelFile(file)) model = loadModelFile(file);
                        else if (isSplatFile(file)) splat = loadSplatFile(file);
                    }
                }
                if (pdf != null) {
                    final int pages = pdf.pageCount();
                    image = pdfPreview(pdf);
                    info = Lang.get("ui.preview.pdf", pages);
                } else if (model != null) {
                    image = GltfPreview.render(model, 0f, MODEL_PREVIEW_SIZE);
                    info = Lang.get("ui.preview.model", model.model().triangleCount());
                } else if (splat != null) {
                    image = splat.renderPreview(MODEL_PREVIEW_SIZE);
                    info = Lang.get("ui.preview.splat", splat.cloud().count);
                } else {
                    if (bytes != null) {
                        final DecodedImage decoded = ImageLoader.decode(bytes);
                        image = toBufferedImage(decoded);
                    } else {
                        // 画像でなければ、動画などから 1 枚取り出す
                        image = MediaTools.thumbnail(text);
                    }
                    if (image != null) info = image.getWidth() + " x " + image.getHeight();
                }
            } catch (Exception e) {
                // プレビューを表示できないだけなので、理由は画面に出すだけにする
            }
            final BufferedImage result = image;
            final String resultInfo = info;
            root.platform.execute(() -> {
                if (generation != previewGeneration) return;
                preview.setImage(result);
                preview.setPlaceholder(Lang.get(result == null ? "ui.preview.failed" : "ui.preview.none"));
                previewInfo.setText(resultInfo);
            });
        });
    }

    private static void worker(String name, Runnable task) {
        final Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    // ---------- 看板への書き込み ----------

    // 入力欄の内容。URL はあとで withUrl で入れる
    private SignSpec spec() {
        // 看板のフォーマットでは回転が Z, Y, X の順に並ぶ
        return new SignSpec("",
                posX.getValue(), posY.getValue(), posZ.getValue(),
                width.getValue(), height.getValue(),
                rotZ.getValue(), rotY.getValue(), rotX.getValue());
    }

    public void place() {
        if (busy) return;
        final String text = getSource();
        if (text.isEmpty()) {
            setStatus(Lang.get("ui.status.no_source"), true);
            return;
        }
        if (!root.platform.isInWorld()) {
            setStatus(Lang.get("ui.status.not_in_world"), true);
            return;
        }
        final int capacity = root.platform.maxSignLineLength() * 4;
        final SignSpec spec = spec();
        setBusy(true);

        worker("MarumaSign-Upload", () -> {
            String error = null;
            String signText = null;
            try {
                String url = Http.isHttpUrl(text) ? text : upload(new File(text));
                signText = spec.withUrl(url).toText();
                if (signText.length() > capacity) {
                    // 看板に入りきらない場合は URL を短縮する
                    progress(Lang.get("ui.status.shortening"));
                    url = Http.shorten(url);
                    signText = spec.withUrl(url).toText();
                }
                if (signText.length() > capacity) {
                    error = Lang.get("ui.status.too_long", signText.length(), capacity);
                }
            } catch (Exception e) {
                MarumaSign.LOGGER.info("Could not prepare the sign text: " + e);
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }

            final String resultError = error;
            final String resultText = signText;
            root.platform.execute(() -> {
                setBusy(false);
                if (resultError != null) {
                    setStatus(resultError, true);
                    return;
                }
                setStatus(Lang.get("ui.status.ready"), false);
                root.close();
                MarumaSign.requestWrite(resultText);
            });
        });
    }

    private void progress(final String message) {
        root.platform.execute(() -> setStatus(message, false));
    }

    // 3D モデルのファイルを読み込む。JSON ではあるがモデルではないファイルなら null
    private static GltfScene loadModelFile(File file) {
        try {
            return ImageLoader.model(file);
        } catch (IOException e) {
            MarumaSign.LOGGER.info("Could not load the model: " + e.getMessage());
            return null;
        }
    }

    // Gaussian Splatting のファイルを読み込む
    private static SplatScene loadSplatFile(File file) {
        try {
            return ImageLoader.splat(file);
        } catch (IOException e) {
            MarumaSign.LOGGER.info("Could not load the splat file: " + e.getMessage());
            return null;
        }
    }

    private String upload(File file) throws IOException {
        final byte[] bytes = readImageFile(file);
        File packed = null;
        if (bytes != null) {
            try {
                ImageLoader.decode(bytes);
            } catch (IOException e) {
                throw new IOException(Lang.get("ui.status.not_image"));
            }
        } else if (isPdfFile(file)) {
            if (file.length() > PdfFile.MAX_SIZE) throw new IOException(Lang.get("ui.status.file_too_large", 64));
            try {
                // 表示できる PDF かどうかを確かめる
                PdfFile.open(file).close();
            } catch (IOException e) {
                MarumaSign.LOGGER.info("Could not load the PDF: " + e.getMessage());
                throw new IOException(Lang.get("ui.status.not_image"));
            }
        } else if (isStructureFile(file)) {
            if (file.length() > StructureLoader.MAX_SIZE) throw new IOException(Lang.get("ui.status.file_too_large", 64));
            try {
                // 表示できる構造物かどうかを確かめる
                ImageLoader.model(file);
            } catch (IOException e) {
                MarumaSign.LOGGER.info("Could not load the structure: " + e.getMessage());
                throw new IOException(Lang.get("ui.status.not_image"));
            }
        } else if (isModelFile(file)) {
            if (file.length() > ImageLoader.MAX_MODEL_SIZE) throw new IOException(Lang.get("ui.status.file_too_large", 64));
            final byte[] content = Files.readAllBytes(file.toPath());
            try {
                // 表示できるモデルかどうかを確かめる
                ImageLoader.model(file);
                if (!GltfLoader.isGlb(content, content.length)) {
                    // 外部ファイルを参照していても 1 つのファイルで済むように、.glb にまとめる
                    final byte[] glb = GltfPacker.pack(content, ImageLoader.fileResources(file));
                    packed = File.createTempFile("marumasign", ".glb");
                    Files.write(packed.toPath(), glb);
                }
            } catch (IOException e) {
                if (packed != null) packed.delete();
                MarumaSign.LOGGER.info("Could not load the model: " + e.getMessage());
                throw new IOException(Lang.get("ui.status.not_image"));
            }
        } else if (isSplatFile(file)) {
            if (file.length() > ImageLoader.MAX_SPLAT_SIZE) throw new IOException(Lang.get("ui.status.file_too_large", 200));
            final byte[] content = Files.readAllBytes(file.toPath());
            try {
                SplatLoader.load(content, file.getName());
            } catch (IOException e) {
                MarumaSign.LOGGER.info("Could not load the splat file: " + e.getMessage());
                throw new IOException(Lang.get("ui.status.not_image"));
            }
        } else {
            // 画像でなければ、動画や音声として再生できるかを確かめる
            if (file.length() > Http.MAX_UPLOAD_SIZE) throw new IOException(Lang.get("ui.status.file_too_large", 200));
            if (!MediaTools.isPlayable(file)) throw new IOException(Lang.get("ui.status.not_image"));
        }

        final String key = file.getAbsolutePath() + '|' + file.length() + '|' + file.lastModified();
        synchronized (uploaded) {
            final String cached = uploaded.get(key);
            if (cached != null) {
                if (packed != null) packed.delete();
                return cached;
            }
        }

        progress(Lang.get("ui.status.uploading"));
        final String url;
        try {
            url = Http.upload(packed != null ? packed : file);
        } catch (IOException e) {
            throw new IOException(Lang.get("ui.status.upload_failed", String.valueOf(e.getMessage())));
        } finally {
            if (packed != null) packed.delete();
        }
        synchronized (uploaded) {
            uploaded.put(key, url);
        }
        return url;
    }

    // ---------- 動作確認用 ----------

    public void setParameters(float w, float h, float x, float y, float z, float rx, float ry, float rz) {
        width.setValue(w);
        height.setValue(h);
        posX.setValue(x);
        posY.setValue(y);
        posZ.setValue(z);
        rotX.setValue(rx);
        rotY.setValue(ry);
        rotZ.setValue(rz);
    }
}
