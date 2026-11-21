package dev.manhattan;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import imgui.ImDrawData;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImVec4;
import imgui.type.ImInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Mojang RenderPearl / Blaze3D backend for Dear ImGui {@link ImDrawData} (MC 26.3+).
 * Zero OpenGL or GLFW dependencies: renders via {@link RenderPipelines#GUI_TEXTURED_SNIPPET}
 * with {@link PrimitiveTopology#TRIANGLES} and {@link DefaultVertexFormat#POSITION_TEX_COLOR}.
 */
final class ImGuiRenderPearlRenderer {
    private static final int FONT_TEX_ID = 1;
    private static final int VERTEX_STRIDE = 24; // float x, y, z, u, v + int abgr

    private static final RenderPipeline IMGUI_PIPELINE = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("manhattan", "pipeline/imgui"))
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .build();

    /** Opaque (no alpha-blend) pipeline for the docked game viewport so sky / Distant Horizons alpha=0 pixels stay opaque. */
    private static final RenderPipeline GAME_QUAD_PIPELINE = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("manhattan", "pipeline/imgui_game_quad"))
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .build();

    private static GpuTexture fontTexture;
    private static GpuTextureView fontTextureView;
    private static GpuSampler linearSampler;
    private static Projection guiProjection;
    private static ProjectionMatrixBuffer projBuffer;

    private static GpuBuffer vertexBuffer;
    private static long vertexBufferCapacity;
    private static GpuBuffer indexBuffer;
    private static long indexBufferCapacity;
    private static ByteBuffer stagingVtx = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.nativeOrder());
    private static ByteBuffer stagingIdx = ByteBuffer.allocateDirect(32 * 1024).order(ByteOrder.nativeOrder());

    // Docked-viewport snapshot copy of mainRenderTarget color texture
    private static GpuTexture gameCopyTexture;
    private static GpuTextureView gameCopyView;
    private static int gameCopyW, gameCopyH;

    // External texture registry (e.g. ItemIcons block/item atlases)
    private static final Map<Integer, GpuTextureView> texById = new HashMap<>();
    private static final Map<GpuTextureView, Integer> idByTex = new IdentityHashMap<>();
    private static int nextTexId = 2;

    private ImGuiRenderPearlRenderer() {}

    static int registerExternalTexture(GpuTextureView view) {
        if (view == null) return 0;
        Integer existing = idByTex.get(view);
        if (existing != null) return existing;
        int id = nextTexId++;
        texById.put(id, view);
        idByTex.put(view, id);
        return id;
    }

    static void clearExternalTextures() {
        texById.clear();
        idByTex.clear();
        nextTexId = 2;
    }

    static void init() {
        GpuDevice dev = RenderSystem.getDevice();
        if (linearSampler == null) {
            linearSampler = dev.createSampler(
                    AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
        }
        if (guiProjection == null) {
            guiProjection = new Projection();
        }
        if (projBuffer == null) {
            projBuffer = new ProjectionMatrixBuffer("Manhattan ImGui");
        }
        rebuildFontsTexture();
    }

    static void rebuildFontsTexture() {
        destroyFontsTexture();
        ImGuiIO io = ImGui.getIO();
        ImInt w = new ImInt();
        ImInt h = new ImInt();
        ByteBuffer rgba = io.getFonts().getTexDataAsRGBA32(w, h);
        int width = Math.max(1, w.get());
        int height = Math.max(1, h.get());

        GpuDevice dev = RenderSystem.getDevice();
        fontTexture = dev.createTexture("Manhattan ImGui Font",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        fontTextureView = dev.createTextureView(fontTexture);

        try (NativeImage img = new NativeImage(width, height, false)) {
            rgba.rewind();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int r = rgba.get() & 0xFF;
                    int g = rgba.get() & 0xFF;
                    int b = rgba.get() & 0xFF;
                    int a = rgba.get() & 0xFF;
                    int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                    img.setPixelABGR(x, y, abgr);
                }
            }
            CommandEncoder enc = dev.createCommandEncoder();
            enc.writeToTexture(fontTexture, img);
            enc.submit();
        }
        io.getFonts().setTexID(FONT_TEX_ID);
    }

    static void destroyFontsTexture() {
        if (fontTextureView != null) {
            fontTextureView.close();
            fontTextureView = null;
        }
        if (fontTexture != null) {
            fontTexture.close();
            fontTexture = null;
        }
    }

    /**
     * Renders the docked game viewport (when {@code dockedShell} is true) and all Dear ImGui draw lists
     * directly into {@code mc.gameRenderer.mainRenderTarget()}.
     */
    static void renderDrawData(Minecraft mc, ImDrawData drawData, boolean dockedShell, float[] fittedViewport) {
        if (mc == null || mc.gameRenderer == null || drawData == null) return;
        RenderTarget mainTarget = mc.gameRenderer.mainRenderTarget();
        if (mainTarget == null) return;
        GpuTexture mainColor = mainTarget.getColorTexture();
        GpuTextureView mainColorView = mainTarget.getColorTextureView();
        if (mainColor == null || mainColorView == null) return;

        CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(IMGUI_PIPELINE);
        if (compiled == null) {
            compiled = RenderSystem.getCompiledPipeline(IMGUI_PIPELINE);
        }
        if (compiled == null) return;

        float displayW = drawData.getDisplaySizeX();
        float displayH = drawData.getDisplaySizeY();
        float fbScaleX = drawData.getFramebufferScaleX();
        float fbScaleY = drawData.getFramebufferScaleY();
        int fbW = Math.max(1, (int) (displayW * fbScaleX));
        int fbH = Math.max(1, (int) (displayH * fbScaleY));
        if (displayW <= 0f || displayH <= 0f) return;

        GpuDevice dev = RenderSystem.getDevice();
        CommandEncoder enc = dev.createCommandEncoder();

        // 1. If the docked Workspace is open, snapshot the game color texture, clear to BG, and draw it fitted.
        boolean drawGameQuad = false;
        if (dockedShell && fittedViewport != null && fittedViewport.length == 4) {
            ensureGameCopy(dev, mainColor, mainTarget.width, mainTarget.height);
            if (gameCopyTexture != null && gameCopyView != null) {
                enc.copyTextureToTexture(mainColor, gameCopyTexture, 0, 0, 0, 0, 0, mainTarget.width, mainTarget.height);
                enc.clearColorTexture(mainColor, new Vector4f(
                        UiTheme.r(UiTheme.BG), UiTheme.g(UiTheme.BG), UiTheme.b(UiTheme.BG), 1.0f));
                drawGameQuad = true;
            }
        }

        // 2. Pack docked game quad + all ImDrawList vertices/indices into contiguous GPU buffers.
        int totalVtx = drawGameQuad ? 4 : 0;
        int totalIdx = drawGameQuad ? 6 : 0;
        int lists = drawData.getCmdListsCount();
        for (int i = 0; i < lists; i++) {
            totalVtx += drawData.getCmdListVtxBufferSize(i);
            totalIdx += drawData.getCmdListIdxBufferSize(i);
        }
        if (totalVtx == 0 || totalIdx == 0) {
            enc.submit();
            return;
        }

        int totalVtxBytes = totalVtx * VERTEX_STRIDE;
        int totalIdxBytes = totalIdx * 2;
        ensureStaging(totalVtxBytes, totalIdxBytes);
        ensureGpuBuffers(dev, totalVtxBytes, totalIdxBytes);

        stagingVtx.clear();
        stagingIdx.clear();

        if (drawGameQuad) {
            float x0 = fittedViewport[0], y0 = fittedViewport[1];
            float x1 = x0 + fittedViewport[2], y1 = y0 + fittedViewport[3];
            // mainRenderTarget color texture is stored bottom-up on Vulkan/RenderPearl -> flip V (1 at top, 0 at bottom).
            putVtx(stagingVtx, x0, y0, 0f, 1f, 0xFFFFFFFF);
            putVtx(stagingVtx, x1, y0, 1f, 1f, 0xFFFFFFFF);
            putVtx(stagingVtx, x1, y1, 1f, 0f, 0xFFFFFFFF);
            putVtx(stagingVtx, x0, y1, 0f, 0f, 0xFFFFFFFF);
            stagingIdx.putShort((short) 0).putShort((short) 1).putShort((short) 2);
            stagingIdx.putShort((short) 0).putShort((short) 2).putShort((short) 3);
        }

        int[] listVtxBase = new int[lists];
        int[] listIdxBase = new int[lists];
        int curVtx = drawGameQuad ? 4 : 0;
        int curIdx = drawGameQuad ? 6 : 0;

        for (int i = 0; i < lists; i++) {
            listVtxBase[i] = curVtx;
            listIdxBase[i] = curIdx;

            int vCount = drawData.getCmdListVtxBufferSize(i);
            ByteBuffer srcVtx = drawData.getCmdListVtxBufferData(i).order(ByteOrder.nativeOrder());
            for (int v = 0; v < vCount; v++) {
                float x = srcVtx.getFloat();
                float y = srcVtx.getFloat();
                float u = srcVtx.getFloat();
                float vv = srcVtx.getFloat();
                int col = srcVtx.getInt();
                putVtx(stagingVtx, x, y, u, vv, col);
            }

            int iCount = drawData.getCmdListIdxBufferSize(i);
            ByteBuffer srcIdx = drawData.getCmdListIdxBufferData(i).order(ByteOrder.nativeOrder());
            for (int k = 0; k < iCount; k++) {
                stagingIdx.putShort(srcIdx.getShort());
            }

            curVtx += vCount;
            curIdx += iCount;
        }

        stagingVtx.flip();
        stagingIdx.flip();

        enc.writeToBuffer(vertexBuffer.slice(0, totalVtxBytes), stagingVtx);
        enc.writeToBuffer(indexBuffer.slice(0, totalIdxBytes), stagingIdx);

        // 3. Configure Vulkan/RenderPearl ortho projection & default uniforms, then issue RenderPass draws.
        RenderSystem.backupProjectionMatrix();
        guiProjection.setupOrtho(-1000.0f, 1000.0f, displayW, displayH, true);
        RenderSystem.setProjectionMatrix(projBuffer.getBuffer(guiProjection), ProjectionType.ORTHOGRAPHIC);
        GpuBufferSlice dynTransform = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f().identity());

        float clipOffX = drawData.getDisplayPosX();
        float clipOffY = drawData.getDisplayPosY();
        ImVec4 clipRect = new ImVec4();

        try (RenderPass pass = enc.createRenderPass(() -> "Manhattan ImGui", mainColorView, Optional.empty())) {
            pass.setVertexBuffer(0, vertexBuffer.slice(0, totalVtxBytes));
            pass.setIndexBuffer(indexBuffer, IndexType.SHORT);

            if (drawGameQuad) {
                CompiledRenderPipeline gameCompiled = RenderSystem.getCompiledPipelineNullable(GAME_QUAD_PIPELINE);
                if (gameCompiled == null) gameCompiled = RenderSystem.getCompiledPipeline(GAME_QUAD_PIPELINE);
                pass.setPipeline(gameCompiled != null ? gameCompiled : compiled);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", dynTransform);
                pass.disableScissor();
                pass.setUniform("Sampler0", gameCopyView, linearSampler);
                pass.drawIndexed(6, 1, 0, 0, 0);
            }

            pass.setPipeline(compiled);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynTransform);

            for (int i = 0; i < lists; i++) {
                int cmdCount = drawData.getCmdListCmdBufferSize(i);
                for (int c = 0; c < cmdCount; c++) {
                    drawData.getCmdListCmdBufferClipRect(clipRect, i, c);
                    float cx1 = (clipRect.x - clipOffX) * fbScaleX;
                    float cy1 = (clipRect.y - clipOffY) * fbScaleY;
                    float cx2 = (clipRect.z - clipOffX) * fbScaleX;
                    float cy2 = (clipRect.w - clipOffY) * fbScaleY;
                    if (cx2 <= cx1 || cy2 <= cy1) continue;

                    int sx = Math.max(0, (int) cx1);
                    int sy = Math.max(0, (int) cy1);
                    int ex = Math.min(fbW, (int) cx2);
                    int ey = Math.min(fbH, (int) cy2);
                    int sw = ex - sx;
                    int sh = ey - sy;
                    if (sw <= 0 || sh <= 0) continue;

                    int texId = (int) drawData.getCmdListCmdBufferTextureId(i, c);
                    GpuTextureView texView = resolveTexture(texId);
                    if (texView == null) continue;

                    int elemCount = drawData.getCmdListCmdBufferElemCount(i, c);
                    int idxOffset = listIdxBase[i] + drawData.getCmdListCmdBufferIdxOffset(i, c);
                    int vtxOffset = listVtxBase[i] + drawData.getCmdListCmdBufferVtxOffset(i, c);

                    pass.enableScissor(sx, sy, sw, sh);
                    pass.setUniform("Sampler0", texView, linearSampler);
                    pass.drawIndexed(elemCount, 1, idxOffset, vtxOffset, 0);
                }
            }
        } finally {
            RenderSystem.restoreProjectionMatrix();
        }

        enc.submit();
    }

    private static GpuTextureView resolveTexture(int texId) {
        if (texId == FONT_TEX_ID || texId == 0) return fontTextureView;
        return texById.getOrDefault(texId, fontTextureView);
    }

    private static void ensureGameCopy(GpuDevice dev, GpuTexture mainColor, int w, int h) {
        if (gameCopyTexture != null && !gameCopyTexture.isClosed() && gameCopyW == w && gameCopyH == h) {
            return;
        }
        if (gameCopyView != null) {
            gameCopyView.close();
            gameCopyView = null;
        }
        if (gameCopyTexture != null) {
            gameCopyTexture.close();
            gameCopyTexture = null;
        }
        gameCopyTexture = dev.createTexture("Manhattan Game Copy",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                mainColor.getFormat(), w, h, 1, 1);
        gameCopyView = dev.createTextureView(gameCopyTexture);
        gameCopyW = w;
        gameCopyH = h;
    }

    private static void ensureStaging(int vtxBytes, int idxBytes) {
        if (stagingVtx.capacity() < vtxBytes) {
            stagingVtx = ByteBuffer.allocateDirect(Math.max(vtxBytes, stagingVtx.capacity() * 2)).order(ByteOrder.nativeOrder());
        }
        if (stagingIdx.capacity() < idxBytes) {
            stagingIdx = ByteBuffer.allocateDirect(Math.max(idxBytes, stagingIdx.capacity() * 2)).order(ByteOrder.nativeOrder());
        }
    }

    private static void ensureGpuBuffers(GpuDevice dev, long vtxBytes, long idxBytes) {
        if (vertexBuffer == null || vertexBuffer.isClosed() || vertexBufferCapacity < vtxBytes) {
            if (vertexBuffer != null && !vertexBuffer.isClosed()) vertexBuffer.close();
            vertexBufferCapacity = Math.max(vtxBytes, 64 * 1024L);
            vertexBuffer = dev.createBuffer(() -> "Manhattan ImGui VBO",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, vertexBufferCapacity);
        }
        if (indexBuffer == null || indexBuffer.isClosed() || indexBufferCapacity < idxBytes) {
            if (indexBuffer != null && !indexBuffer.isClosed()) indexBuffer.close();
            indexBufferCapacity = Math.max(idxBytes, 32 * 1024L);
            indexBuffer = dev.createBuffer(() -> "Manhattan ImGui IBO",
                    GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, indexBufferCapacity);
        }
    }

    private static void putVtx(ByteBuffer dst, float x, float y, float u, float v, int abgr) {
        dst.putFloat(x).putFloat(y).putFloat(0.0f).putFloat(u).putFloat(v).putInt(abgr);
    }
}
