package com.winlator.renderer.material;

import android.opengl.GLES20;
import android.opengl.GLES30;

/**
 * SGSRMaterial implements Snapdragon Game Super Resolution v1 as a post-processing
 * shader pass. It upscales and sharpens the composited X11 frame before display.
 *
 * Requires OpenGL ES 3.1 (for textureGather). The GLSurfaceView must be configured
 * with EGL version 3 for this to work — see note in GLRenderer.
 *
 * Usage in GLRenderer:
 *   1. Render windows normally into an offscreen FBO (the "scene" framebuffer)
 *   2. Bind the default framebuffer
 *   3. Call sgsrMaterial.apply(sceneTextureId, inputWidth, inputHeight, outputWidth, outputHeight)
 */
public class SGSRMaterial extends ShaderMaterial {

    // Vertex shader: draws a fullscreen quad using two triangles.
    // Position is in normalized [-1,1] clip space; UV is [0,1].
    // No xform needed since this always covers the full screen.
    @Override
    protected String getVertexShader() {
        return "#version 300 es\n" +
               "layout(location = 0) in vec2 position;\n" +
               "layout(location = 0) out highp vec4 in_TEXCOORD0;\n" +
               "void main() {\n" +
               "    // position is in [0,1] to match quadVertices in GLRenderer\n" +
               "    in_TEXCOORD0 = vec4(position.x, position.y, 0.0, 1.0);\n" +
               "    gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);\n" +
               "}\n";
    }

    // Fragment shader: SGSR v1 algorithm directly from Qualcomm's mobile GLSL implementation.
    // BSD-3-Clause: Copyright (c) 2025, Qualcomm Innovation Center, Inc.
    @Override
    protected String getFragmentShader() {
        return "#version 300 es\n" +
               "precision mediump float;\n" +
               "precision highp int;\n" +
               "\n" +
               "#define OperationMode 1\n" +
               "#define EdgeThreshold 8.0/255.0\n" +
               "#define EdgeSharpness 2.0\n" +
               "\n" +
               "uniform highp vec4 ViewportInfo[1];\n" +
               "uniform mediump sampler2D ps0;\n" +
               "\n" +
               "layout(location=0) in highp vec4 in_TEXCOORD0;\n" +
               "layout(location=0) out vec4 out_Target0;\n" +
               "\n" +
               "float fastLanczos2(float x) {\n" +
               "    float wA = x - 4.0;\n" +
               "    float wB = x * wA - wA;\n" +
               "    wA *= wA;\n" +
               "    return wB * wA;\n" +
               "}\n" +
               "\n" +
               "vec2 weightY(float dx, float dy, float c, float std) {\n" +
               "    float x = ((dx*dx)+(dy*dy)) * 0.55 + clamp(abs(c)*std, 0.0, 1.0);\n" +
               "    float w = fastLanczos2(x);\n" +
               "    return vec2(w, w * c);\n" +
               "}\n" +
               "\n" +
               "void main() {\n" +
               "    int mode = OperationMode;\n" +
               "    float edgeThreshold = EdgeThreshold;\n" +
               "    float edgeSharpness = EdgeSharpness;\n" +
               "\n" +
               "    vec4 color;\n" +
               "    if (mode == 1)\n" +
               "        color.xyz = textureLod(ps0, in_TEXCOORD0.xy, 0.0).xyz;\n" +
               "    else\n" +
               "        color.xyzw = textureLod(ps0, in_TEXCOORD0.xy, 0.0).xyzw;\n" +
               "\n" +
               "    if (mode != 4) {\n" +
               "        highp vec2 imgCoord = ((in_TEXCOORD0.xy * ViewportInfo[0].zw) + vec2(-0.5, 0.5));\n" +
               "        highp vec2 imgCoordPixel = floor(imgCoord);\n" +
               "        highp vec2 coord = (imgCoordPixel * ViewportInfo[0].xy);\n" +
               "        vec2 pl = (imgCoord + (-imgCoordPixel));\n" +
               "        vec4 left = textureGather(ps0, coord, mode);\n" +
               "\n" +
               "        float edgeVote = abs(left.z - left.y) + abs(color[mode] - left.y) + abs(color[mode] - left.z);\n" +
               "        if (edgeVote > edgeThreshold) {\n" +
               "            coord.x += ViewportInfo[0].x;\n" +
               "\n" +
               "            vec4 right   = textureGather(ps0, coord + highp vec2(ViewportInfo[0].x, 0.0), mode);\n" +
               "            vec4 upDown;\n" +
               "            upDown.xy    = textureGather(ps0, coord + highp vec2(0.0, -ViewportInfo[0].y), mode).wz;\n" +
               "            upDown.zw    = textureGather(ps0, coord + highp vec2(0.0,  ViewportInfo[0].y), mode).yx;\n" +
               "\n" +
               "            float mean = (left.y + left.z + right.x + right.w) * 0.25;\n" +
               "            left   -= vec4(mean);\n" +
               "            right  -= vec4(mean);\n" +
               "            upDown -= vec4(mean);\n" +
               "            color.w = color[mode] - mean;\n" +
               "\n" +
               "            float sum = (abs(left.x)+abs(left.y)+abs(left.z)+abs(left.w)) +\n" +
               "                        (abs(right.x)+abs(right.y)+abs(right.z)+abs(right.w)) +\n" +
               "                        (abs(upDown.x)+abs(upDown.y)+abs(upDown.z)+abs(upDown.w));\n" +
               "            float std = 2.181818 / sum;\n" +
               "\n" +
               "            vec2 aWY  = weightY(pl.x,      pl.y+1.0,  upDown.x, std);\n" +
               "            aWY      += weightY(pl.x-1.0,  pl.y+1.0,  upDown.y, std);\n" +
               "            aWY      += weightY(pl.x-1.0,  pl.y-2.0,  upDown.z, std);\n" +
               "            aWY      += weightY(pl.x,      pl.y-2.0,  upDown.w, std);\n" +
               "            aWY      += weightY(pl.x+1.0,  pl.y-1.0,  left.x,   std);\n" +
               "            aWY      += weightY(pl.x,      pl.y-1.0,  left.y,   std);\n" +
               "            aWY      += weightY(pl.x,      pl.y,      left.z,   std);\n" +
               "            aWY      += weightY(pl.x+1.0,  pl.y,      left.w,   std);\n" +
               "            aWY      += weightY(pl.x-1.0,  pl.y-1.0,  right.x,  std);\n" +
               "            aWY      += weightY(pl.x-2.0,  pl.y-1.0,  right.y,  std);\n" +
               "            aWY      += weightY(pl.x-2.0,  pl.y,      right.z,  std);\n" +
               "            aWY      += weightY(pl.x-1.0,  pl.y,      right.w,  std);\n" +
               "\n" +
               "            float finalY = aWY.y / aWY.x;\n" +
               "            float maxY = max(max(left.y, left.z), max(right.x, right.w));\n" +
               "            float minY = min(min(left.y, left.z), min(right.x, right.w));\n" +
               "            finalY = clamp(edgeSharpness * finalY, minY, maxY);\n" +
               "\n" +
               "            float deltaY = finalY - color.w;\n" +
               "            deltaY = clamp(deltaY, -23.0/255.0, 23.0/255.0);\n" +
               "\n" +
               "            color.x = clamp(color.x + deltaY, 0.0, 1.0);\n" +
               "            color.y = clamp(color.y + deltaY, 0.0, 1.0);\n" +
               "            color.z = clamp(color.z + deltaY, 0.0, 1.0);\n" +
               "        }\n" +
               "    }\n" +
               "\n" +
               "    color.w = 1.0;\n" +
               "    out_Target0 = color;\n" +
               "}\n";
    }

    @Override
    public void use() {
        super.use();
        setUniformNames("ViewportInfo", "ps0");
    }

    /**
     * Apply the SGSR upscaling pass.
     *
     * Call this after all windows and cursor have been rendered into the offscreen FBO,
     * and after binding the default framebuffer (0) as the render target.
     *
     * @param textureId   The GL texture ID of the composited scene (from the offscreen FBO)
     * @param inputW      Width of the input texture (the Wine desktop resolution)
     * @param inputH      Height of the input texture
     * @param outputW     Width of the output (the physical screen)
     * @param outputH     Height of the output
     */
    public void apply(int textureId, int inputW, int inputH, int outputW, int outputH) {
        use();

        // ViewportInfo: {1/inputW, 1/inputH, inputW, inputH}
        // The shader uses xy to offset UVs by one texel, and zw to convert UV to pixel coords.
        float[] viewportInfo = new float[] {
            1.0f / inputW,
            1.0f / inputH,
            (float) inputW,
            (float) inputH
        };
        GLES20.glUniform4fv(getUniformLocation("ViewportInfo"), 1, viewportInfo, 0);

        // Bind the scene texture to unit 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glUniform1i(getUniformLocation("ps0"), 0);
    }
}
