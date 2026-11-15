package dev.baritonequeue;

import com.mojang.blaze3d.opengl.GlTexture;
import imgui.ImDrawList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Item pictures for the workspace, straight from the game's texture atlases: the item's own sprite
 * ({@code item/<id>}) for tools, food and other flat items, and the block texture ({@code block/<id>}, its side or
 * top) for block items. Draws nothing when neither exists, so the caller can fall back to letters.
 */
final class ItemIcons {
    private ItemIcons() {}

    private record Icon(int texId, float u0, float v0, float u1, float v1) {}

    private static final Icon NONE = new Icon(0, 0, 0, 0, 0);
    private static final Map<String, Icon> cache = new HashMap<>();

    /** Draws the icon for {@code id} (e.g. "diamond_pickaxe") into the square; false if there is none. */
    static boolean draw(ImDrawList dl, String id, float x0, float y0, float x1, float y1) {
        Icon ic = cache.computeIfAbsent(id, ItemIcons::find);
        if (ic.texId() == 0) return false;
        dl.addImage(ic.texId(), x0, y0, x1, y1, ic.u0(), ic.v0(), ic.u1(), ic.v1());
        return true;
    }

    /** After a resource reload the atlases are new textures. */
    static void clear() {
        cache.clear();
    }

    private static Icon find(String id) {
        try {
            String base = id.replace("waxed_", "");
            Icon ic = sprite(TextureAtlas.LOCATION_ITEMS, "item/" + id);
            if (ic != null) return ic;
            String[] blockNames = {base, base + "_side", base + "_top", base + "_front",
                    base.replace("_block", ""), base.endsWith("s") ? base.substring(0, base.length() - 1) : base,
                    base.replace("_slab", "").replace("_stairs", "").replace("_wall", ""),
                    base.replace("_slab", "_planks").replace("_stairs", "_planks")};
            for (String n : blockNames) {
                ic = sprite(TextureAtlas.LOCATION_BLOCKS, "block/" + n);
                if (ic != null) return ic;
            }
        } catch (Throwable ignored) {
        }
        return NONE;
    }

    private static Icon sprite(Identifier atlasId, String path) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return null;
        if (!(mc.getTextureManager().getTexture(atlasId) instanceof TextureAtlas atlas)) return null;
        TextureAtlasSprite sp = atlas.getSprite(Identifier.withDefaultNamespace(path));
        if (sp == null || sp.contents().name().equals(MissingTextureAtlasSprite.getLocation())) return null;
        if (!(atlas.getTexture() instanceof GlTexture gl)) return null;
        return new Icon(gl.glId(), sp.getU0(), sp.getV0(), sp.getU1(), sp.getV1());
    }
}
