package marumasa.marumasa_sign.core;

import marumasa.marumasa_sign.core.ui.Theme;

import java.util.ArrayList;
import java.util.List;

public final class WidthChecker {

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

    public static void main(String[] args) {
        int overflowCount = 0;
        final List<String> overflows = new ArrayList<String>();

        for (String lang : LANGUAGES) {
            Lang.select(lang);

            // Checks with component bounds and padding
            check(lang, "ui.browse", 74 - 10, overflows);
            check(lang, "ui.width", 36, overflows);
            check(lang, "ui.height", 36, overflows);
            check(lang, "ui.threads", 136, overflows);
            check(lang, "ui.clear_cache", 132 - 12, overflows);
            check(lang, "ui.volume", 136, overflows);
            check(lang, "ui.place", 128 - 12, overflows);
            check(lang, "ui.cancel", 84 - 12, overflows);
            check(lang, "browser.location", 56, overflows);
            check(lang, "browser.up", 74 - 10, overflows);
            check(lang, "browser.open", 84 - 12, overflows);
            check(lang, "ui.status.ready", 524, overflows);
        }

        System.out.println("=== Width Overflow Report ===");
        System.out.println("Total checks over limit: " + overflows.size());
        for (String msg : overflows) {
            System.out.println("  " + msg);
        }
    }

    private static void check(String lang, String key, int maxWidth, List<String> overflows) {
        final String text = Lang.get(key);
        final int w = Theme.textWidth(text);
        if (w > maxWidth) {
            overflows.add(String.format("[%s] %s: '%s' width=%d > max=%d (overflow %d px)",
                    lang, key, text, w, maxWidth, w - maxWidth));
        }
    }
}
