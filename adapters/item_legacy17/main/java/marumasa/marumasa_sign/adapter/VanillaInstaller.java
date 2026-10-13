package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.item.EntityItem;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * LiteLoader (Vanilla) 向けにゲームのフィールドや表を差し替える。
 */
final class VanillaInstaller {

    private static VanillaRenderItem customRenderItem;
    private static VanillaItemRenderer customItemRenderer;

    private static Field entityRenderMapField;
    private static Field renderManagerItemRendererField;
    private static Field entityRendererItemRendererField;
    private static Field guiScreenItemRenderField;
    private static Field guiIngameItemRendererField;

    private VanillaInstaller() {
    }

    static void install(Minecraft mc) {
        if (customRenderItem == null) {
            customRenderItem = new VanillaRenderItem();
            customRenderItem.setRenderManager(RenderManager.instance);
        }
        if (customItemRenderer == null) {
            customItemRenderer = new VanillaItemRenderer(mc);
        }

        // 1. 地面および額縁のレンダラー (RenderManager の entityRenderMap)
        final RenderManager renderManager = RenderManager.instance;
        if (renderManager != null) {
            if (entityRenderMapField == null) {
                entityRenderMapField = Reflect.fieldOfType(RenderManager.class, Map.class);
            }
            final Map<Class<?>, Object> map = Reflect.get(entityRenderMapField, renderManager);
            if (map != null && map.get(EntityItem.class) != customRenderItem) {
                map.put(EntityItem.class, customRenderItem);
            }

            // 2. 三人称手持ちのレンダラー (RenderManager の itemRenderer)
            if (renderManagerItemRendererField == null) {
                renderManagerItemRendererField = Reflect.fieldOfType(RenderManager.class, ItemRenderer.class);
            }
            if (Reflect.get(renderManagerItemRendererField, renderManager) != customItemRenderer) {
                Reflect.set(renderManagerItemRendererField, renderManager, customItemRenderer);
            }
        }

        // 3. 一人称手持ちのレンダラー (EntityRenderer の itemRenderer)
        if (mc.entityRenderer != null) {
            if (entityRendererItemRendererField == null) {
                entityRendererItemRendererField = Reflect.fieldOfType(EntityRenderer.class, ItemRenderer.class);
            }
            if (Reflect.get(entityRendererItemRendererField, mc.entityRenderer) != customItemRenderer) {
                Reflect.set(entityRendererItemRendererField, mc.entityRenderer, customItemRenderer);
            }
        }

        // 4. GUI コンテナのレンダラー (GuiScreen の static itemRender)
        if (guiScreenItemRenderField == null) {
            guiScreenItemRenderField = Reflect.fieldOfType(GuiScreen.class, RenderItem.class);
        }
        if (Reflect.get(guiScreenItemRenderField, null) != customRenderItem) {
            // このフィールドは書き換えられない指定 (static final) になっている
            Reflect.setStaticFinal(guiScreenItemRenderField, customRenderItem);
        }

        // 5. ホットバーのレンダラー (GuiIngame の itemRenderer)
        if (mc.ingameGUI != null) {
            if (guiIngameItemRendererField == null) {
                guiIngameItemRendererField = Reflect.fieldOfType(GuiIngame.class, RenderItem.class);
            }
            if (Reflect.get(guiIngameItemRendererField, mc.ingameGUI) != customRenderItem) {
                Reflect.set(guiIngameItemRendererField, mc.ingameGUI, customRenderItem);
            }
        }
    }
}
