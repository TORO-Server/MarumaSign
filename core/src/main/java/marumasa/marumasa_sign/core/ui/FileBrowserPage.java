package marumasa.marumasa_sign.core.ui;

import marumasa.marumasa_sign.core.Lang;
import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.media.MediaTools;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 画像ファイルを選ぶ画面。
 */
final class FileBrowserPage extends Page {

    private static final String[] EXTENSIONS = {".png", ".jpg", ".jpeg", ".gif", ".apng", ".glb", ".gltf", ".pdf"};

    private final MainPage owner;
    private final TextField location;
    private final ListBox list;
    private final Button open;

    // 表示中のフォルダ。null のときはドライブの一覧
    private File directory;
    private final List<File> entries = new ArrayList<File>();

    FileBrowserPage(UiRoot root, MainPage owner) {
        super(root);
        this.owner = owner;

        add(new Label(Lang.get("browser.location")).bounds(12, 38, 56, 21));
        location = add(new TextField().onEnter(this::locationEntered));
        location.bounds(70, 38, 386, 21);
        add(new Button(Lang.get("browser.up"), this::up)).bounds(462, 37, 74, 23);

        list = add(new ListBox().onSelect(this::selectionChanged).onActivate(this::activate));
        list.bounds(12, 66, 524, 268);

        add(new Label(Lang.get("browser.filter"))).bounds(12, 348, 340, 16);
        open = add(new Button(Lang.get("browser.open"), this::activate));
        open.bounds(362, 344, 84, 24);
        final Button cancel = add(new Button(Lang.get("ui.cancel"), root::showMainPage));
        cancel.bounds(452, 344, 84, 24);

        defaultButton = open;
        cancelButton = cancel;

        navigate(startDirectory());
    }

    @Override
    public String title() {
        return Lang.get("browser.title");
    }

    @Override
    public void shown() {
        setFocus(list);
    }

    private File startDirectory() {
        // 入力欄がファイルを指していれば、そのフォルダから始める
        final String current = owner.getSource();
        if (!current.isEmpty()) {
            final File parent = new File(current).getAbsoluteFile().getParentFile();
            if (parent != null && parent.isDirectory()) return parent;
        }
        final String last = MarumaSign.config().getLastDirectory();
        if (!last.isEmpty() && new File(last).isDirectory()) return new File(last);

        final File home = new File(System.getProperty("user.home", "."));
        final File pictures = new File(home, "Pictures");
        return pictures.isDirectory() ? pictures : home;
    }

    private static boolean isImage(File file) {
        final String name = file.getName().toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) {
            if (name.endsWith(extension)) return true;
        }
        return MediaTools.hasMediaExtension(name);
    }

    private static final Comparator<File> BY_NAME = new Comparator<File>() {
        @Override
        public int compare(File a, File b) {
            return a.getName().compareToIgnoreCase(b.getName());
        }
    };

    private void navigate(File target) {
        final List<File> folders = new ArrayList<File>();
        final List<File> files = new ArrayList<File>();
        if (target == null) {
            final File[] roots = File.listRoots();
            if (roots != null) folders.addAll(Arrays.asList(roots));
        } else {
            final File[] children = target.listFiles();
            if (children == null) return;
            for (File child : children) {
                if (child.isHidden()) continue;
                if (child.isDirectory()) folders.add(child);
                else if (isImage(child)) files.add(child);
            }
            folders.sort(BY_NAME);
            files.sort(BY_NAME);
        }

        directory = target;
        entries.clear();
        entries.addAll(folders);
        entries.addAll(files);

        final List<String> names = new ArrayList<String>();
        for (File entry : entries) {
            // ドライブは名前が空になるのでパスをそのまま表示する
            final String name = entry.getName().isEmpty() ? entry.getPath() : entry.getName();
            names.add(entry.isDirectory() && !name.endsWith(File.separator) ? name + File.separator : name);
        }
        list.setItems(names);
        location.setText(target == null ? "" : target.getAbsolutePath());
        open.enabled = false;
        repaint();
    }

    private void up() {
        if (directory == null) return;
        navigate(directory.getAbsoluteFile().getParentFile());
    }

    private void locationEntered() {
        final String text = location.getText().trim();
        if (text.isEmpty()) {
            navigate(null);
            return;
        }
        final File file = new File(text);
        if (file.isDirectory()) {
            navigate(file);
            setFocus(list);
        } else if (file.isFile()) {
            choose(file);
        }
    }

    private void selectionChanged() {
        open.enabled = list.getSelected() >= 0;
        repaint();
    }

    private void activate() {
        final int index = list.getSelected();
        if (index < 0 || index >= entries.size()) return;
        final File entry = entries.get(index);
        if (entry.isDirectory()) {
            navigate(entry);
        } else {
            choose(entry);
        }
    }

    private void choose(File file) {
        final File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null) MarumaSign.config().setLastDirectory(parent.getAbsolutePath());
        owner.setSource(file.getAbsolutePath());
        root.showMainPage();
    }
}
