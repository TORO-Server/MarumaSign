package marumasa.marumasa_sign.core;

import marumasa.marumasa_sign.core.ui.Key;
import marumasa.marumasa_sign.core.ui.UiRoot;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 全言語の画面見た目を一括で確認するための道具。
 */
public final class AllUiPreview {

    private static final String[] LANGUAGES = {
            "af_za", "ar_sa", "ast_es", "az_az", "ba_ru", "bar", "be_by", "bg_bg", "br_fr", "bs_ba",
            "ca_es", "cs_cz", "cy_gb", "da_dk", "de_at", "de_ch", "de_de", "el_gr", "en_au", "en_ca",
            "en_gb", "en_nz", "en_pt", "en_ud", "en_us", "eo_uy", "es_ar", "es_cl", "es_ec", "es_es",
            "es_mx", "es_uy", "es_ve", "et_ee", "eu_es", "fa_ir", "fi_fi", "fil_ph", "fo_fo", "fr_ca",
            "fr_fr", "fra_de", "fy_nl", "ga_ie", "gd_gb", "gl_es", "haw_us", "he_il", "hi_in", "hr_hr",
            "hu_hu", "hy_am", "id_id", "ig_ng", "io_en", "is_is", "it_it", "ja_jp", "jbo_en", "ka_ge",
            "kk_kz", "kn_in", "ko_kr", "ksh", "kw_gb", "la_va", "lb_lu", "li_li", "lmo", "lt_lt",
            "lv_lv", "mk_mk", "mn_mn", "ms_my", "mt_mt", "nds_de", "nl_be", "nl_nl", "nn_no", "no_no",
            "oc_fr", "ovd", "pl_pl", "pt_br", "pt_pt", "qya_aa", "ro_ro", "rpr", "ru_ru", "se_no",
            "sk_sk", "sl_si", "so_so", "sq_al", "sr_sp", "sv_se", "sxu", "szl", "ta_in", "th_th",
            "tl_ph", "tlh_aa", "tok", "tr_tr", "tt_ru", "uk_ua", "val_es", "vec_it", "vi_vn", "yi_de",
            "yo_ng", "zh_cn", "zh_hk", "zh_tw", "zlm_arab"
    };

    private static void save(FakePlatform platform, UiRoot ui, File file) throws Exception {
        platform.runTasks();
        ui.texture();
        final BufferedImage image = new BufferedImage(platform.width, platform.height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, platform.width, platform.height, platform.pixels, 0, platform.width);
        ImageIO.write(image, "png", file);
    }

    private static void resetPlatform() throws Exception {
        final Field f = MarumaSign.class.getDeclaredField("platform");
        f.setAccessible(true);
        f.set(null, null);
    }

    public static void main(String[] args) throws Exception {
        final File output = new File(args.length > 0 ? args[0] : "build/previews");
        if (!output.isDirectory() && !output.mkdirs()) throw new IllegalStateException("Cannot create " + output);
        final int scale = args.length > 1 ? Integer.parseInt(args[1]) : 2;

        System.out.println("Starting batch preview generation for " + LANGUAGES.length + " languages...");

        final List<String> warnings = new ArrayList<String>();

        for (String lang : LANGUAGES) {
            resetPlatform();
            Lang.select(lang);
            final FakePlatform platform = new FakePlatform(lang);
            MarumaSign.init(platform);
            final UiRoot ui = MarumaSign.ui();
            ui.opened();
            ui.layout(UiRoot.WIDTH * scale, UiRoot.HEIGHT * scale);
            save(platform, ui, new File(output, "main-" + lang + ".png"));

            // ファイル選択画面 (Tab -> Enter)
            ui.keyPressed(Key.TAB, 0);
            ui.keyPressed(Key.ENTER, 0);
            save(platform, ui, new File(output, "browser-" + lang + ".png"));
        }

        System.out.println("Rendered all " + LANGUAGES.length + " languages successfully into " + output);
    }
}
