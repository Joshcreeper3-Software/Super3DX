import java.awt.*;
import java.awt.image.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.nio.file.*;

public class Super3DX {
    public final int width;
    public final int height;
    private final int[] framebuffer;
    private final float[] zBuffer;
    private final BufferedImage image;
    
    private final Matrix4x4 viewMatrix = new Matrix4x4();
    private final Matrix4x4 projMatrix = new Matrix4x4();
    private final Matrix4x4 viewProjMatrix = new Matrix4x4();
    private final Matrix4x4 mvpMatrix = new Matrix4x4();
    
    private float nearClip = 0.1f;
    private float farClip = 1000.0f;
    private float fov = 60.0f;
    private boolean backfaceCulling = true;
    
    private Vec3 lightDir = new Vec3(0.5f, -1.0f, 0.3f).normalize();
    private float ambientIntensity = 0.3f;
    private float diffuseIntensity = 0.7f;
    private float specularIntensity = 0.6f;
    private float shininess = 32.0f;
    private Vec3 cameraPos = new Vec3();
    
    // === THREADING & TILE-BASED ===
    private final ForkJoinPool threadPool;
    private TileRegion[] tileRegions;
    private java.util.List<TriangleBatch>[] tileBatches;
    private boolean useTileBased = false;
    private int tileSize = 32;
    private int numThreads;
    
    // === PROFILING ===
    public int statsDrawCalls, statsTriangles, statsPixels;
    public long statsRasterTime, statsFrameTime;
    
    private ShadowMap shadowMap;
    private final Matrix4x4 lightViewMatrix = new Matrix4x4();
    private final Matrix4x4 lightProjMatrix = new Matrix4x4();
    private final Matrix4x4 lightVP = new Matrix4x4();
    private final Matrix4x4 lightMVP = new Matrix4x4();
    private float shadowBias = 0.005f;
    private float shadowIntensity = 0.35f;
    private final Vertex shadowV0 = new Vertex();
    private final Vertex shadowV1 = new Vertex();
    private final Vertex shadowV2 = new Vertex();
    
    private final Vertex[] clippedVerts = new Vertex[10];

    public enum BlendMode { NONE, ALPHA, ADDITIVE }
    public enum CullMode { NONE, FRONT, BACK }
    public enum RenderMode { SOLID, WIREFRAME, SOLID_WIREFRAME }

    private BlendMode blendMode = BlendMode.NONE;
    private CullMode cullMode = CullMode.BACK;
    private RenderMode renderMode = RenderMode.SOLID;
    private boolean gammaCorrection = false;

    private boolean fogEnabled = false;
    private Color fogColor = new Color(128, 128, 128);
    private float fogNear = 5.0f;
    private float fogFar = 15.0f;
    
    public Super3DX(int width, int height) {
        this.width = width;
        this.height = height;
        this.framebuffer = new int[width * height];
        this.zBuffer = new float[width * height];
        this.image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        
        this.numThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        this.threadPool = new ForkJoinPool(numThreads);
        initTiles(32);
        
        setPerspective(fov, (float)width / height, nearClip, farClip);
    }
    
    public void enableTileBased(boolean enable, int tileSize) {
        this.useTileBased = enable && numThreads > 1;
        if (this.tileSize != tileSize) { this.tileSize = tileSize; initTiles(tileSize); }
    }
    
    @SuppressWarnings("unchecked")
    private void initTiles(int ts) {
        this.tileSize = ts;
        int tx = (width + ts - 1) / ts, ty = (height + ts - 1) / ts;
        tileRegions = new TileRegion[tx * ty];
        tileBatches = new java.util.List[tx * ty];
        for (int y = 0; y < ty; y++) for (int x = 0; x < tx; x++) {
            int idx = y * tx + x;
            tileRegions[idx] = new TileRegion(x * ts, y * ts, Math.min(ts, width - x * ts), Math.min(ts, height - y * ts));
            tileBatches[idx] = new ArrayList<>();
        }
    }
    
    public void setCamera(Vec3 position, Vec3 target, Vec3 up) {
        cameraPos = position;
        viewMatrix.lookAt(position, target, up);
        updateViewProj();
    }
    
    public void setPerspective(float fovDeg, float aspect, float near, float far) {
        this.fov = fovDeg;
        this.nearClip = near;
        this.farClip = far;
        projMatrix.perspective(fovDeg, aspect, near, far);
        updateViewProj();
    }
    
    private void updateViewProj() {
        viewProjMatrix.mul(projMatrix, viewMatrix);
    }
    
    public void clear(int color) {
        Arrays.fill(framebuffer, color);
        Arrays.fill(zBuffer, Float.MAX_VALUE);
    }
    
    public void clear(Color color) {
        clear(color.getRGB());
    }
    
    public void applyFXAA() {
        int[] src = framebuffer.clone();
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int idx = y * width + x;
                int c = src[idx];
                int l = src[y * width + x - 1];
                int rp = src[y * width + x + 1];
                int u = src[(y - 1) * width + x];
                int d = src[(y + 1) * width + x];
                float lumaC = ((c>>16&0xFF)*0.299f + (c>>8&0xFF)*0.587f + (c&0xFF)*0.114f);
                float lumaL = ((l>>16&0xFF)*0.299f + (l>>8&0xFF)*0.587f + (l&0xFF)*0.114f);
                float lumaR = ((rp>>16&0xFF)*0.299f + (rp>>8&0xFF)*0.587f + (rp&0xFF)*0.114f);
                float lumaU = ((u>>16&0xFF)*0.299f + (u>>8&0xFF)*0.587f + (u&0xFF)*0.114f);
                float lumaD = ((d>>16&0xFF)*0.299f + (d>>8&0xFF)*0.587f + (d&0xFF)*0.114f);
                float contrast = Math.max(Math.abs(lumaL - lumaC), Math.abs(lumaR - lumaC));
                contrast = Math.max(contrast, Math.max(Math.abs(lumaU - lumaC), Math.abs(lumaD - lumaC)));
                if (contrast > 0.1f) {
                    int rr = ((c>>16&0xFF)+(l>>16&0xFF)+(rp>>16&0xFF)+(u>>16&0xFF)+(d>>16&0xFF))/5;
                    int g = ((c>> 8&0xFF)+(l>> 8&0xFF)+(rp>> 8&0xFF)+(u>> 8&0xFF)+(d>> 8&0xFF))/5;
                    int b = ((c     &0xFF)+(l     &0xFF)+(rp     &0xFF)+(u     &0xFF)+(d     &0xFF))/5;
                    framebuffer[idx] = (rr << 16) | (g << 8) | b;
                }
            }
        }
    }
    
    public void setBlendMode(BlendMode mode) {
        this.blendMode = mode;
    }

    public void setCullMode(CullMode mode) {
        this.cullMode = mode;
        this.backfaceCulling = mode != CullMode.NONE;
    }

    public void setRenderMode(RenderMode mode) {
        this.renderMode = mode;
    }

    public void setGammaCorrection(boolean enable) {
        this.gammaCorrection = enable;
    }

    public void renderInstanced(Mesh mesh, Matrix4x4[] transforms, Texture texture) {
        for (Matrix4x4 m : transforms) {
            renderMesh(mesh, m, texture);
        }
    }

    public void renderBillboard(Texture texture, Vec3 position, float size) {
        Vec3 forward = new Vec3(0, 0, -1);
        Vec3 right = cameraPos.sub(position).cross(new Vec3(0, 1, 0));
        float rl = right.length();
        if (rl < 1e-6f) right = new Vec3(1, 0, 0); else right = right.scale(1f / rl);
        Vec3 up = new Vec3(0, 1, 0);
        float h = size / 2;
        Vec3[] corners = {
            position.add(right.scale(-h)).add(up.scale(-h)),
            position.add(right.scale( h)).add(up.scale(-h)),
            position.add(right.scale( h)).add(up.scale( h)),
            position.add(right.scale(-h)).add(up.scale( h))
        };
        Vertex[] verts = new Vertex[4];
        float[][] uv = {{0,0},{1,0},{1,1},{0,1}};
        Vec3 n = forward;
        for (int i = 0; i < 4; i++) {
            verts[i] = new Vertex(corners[i], n, Color.WHITE, uv[i][0], uv[i][1]);
        }
        int[] idx = {0,1,2,0,2,3};
        Mesh quad = new Mesh(verts, idx);
        renderMesh(quad, new Matrix4x4(), texture);
    }

    public void renderParticles(ParticleEmitter emitter, Texture texture) {
        for (Particle p : emitter.particles) {
            renderBillboard(texture, p.position, p.size);
        }
    }

    public void applyBloom(float threshold, int passes) {
        int[] src = framebuffer.clone();
        int[] bright = new int[width * height];
        for (int i = 0; i < src.length; i++) {
            int r = (src[i] >> 16) & 0xFF, g = (src[i] >> 8) & 0xFF, b = src[i] & 0xFF;
            float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            if (luma > threshold * 255) {
                bright[i] = src[i];
            }
        }
        int[] blurred = bright.clone();
        for (int p = 0; p < passes; p++) {
            int[] tmp = blurred.clone();
            for (int y = 1; y < height - 1; y++) {
                for (int x = 1; x < width - 1; x++) {
                    int idx = y * width + x;
                    int rr = 0, gg = 0, bb = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int c = tmp[(y + dy) * width + (x + dx)];
                            rr += (c >> 16) & 0xFF; gg += (c >> 8) & 0xFF; bb += c & 0xFF;
                        }
                    }
                    blurred[idx] = (rr / 9 << 16) | (gg / 9 << 8) | (bb / 9);
                }
            }
        }
        for (int i = 0; i < src.length; i++) {
            int sr = (src[i] >> 16) & 0xFF, sg = (src[i] >> 8) & 0xFF, sb = src[i] & 0xFF;
            int br = (blurred[i] >> 16) & 0xFF, bg = (blurred[i] >> 8) & 0xFF, bb = blurred[i] & 0xFF;
            framebuffer[i] = (Math.min(255, sr + br) << 16) | (Math.min(255, sg + bg) << 8) | Math.min(255, sb + bb);
        }
    }

    public void setFog(Color color, float near, float far) {
        this.fogColor = color;
        this.fogNear = near;
        this.fogFar = far;
        this.fogEnabled = true;
    }

    public void disableFog() {
        this.fogEnabled = false;
    }

    public void setSpecular(float intensity, float shininess) {
        this.specularIntensity = intensity;
        this.shininess = shininess;
    }
    
    public void applySkinning(Mesh mesh, Skeleton skeleton, AnimationClip clip, float time) {
        mesh.saveBindPose();
        Matrix4x4[] boneMats = new Matrix4x4[skeleton.numBones];
        for (int i = 0; i < skeleton.numBones; i++) boneMats[i] = new Matrix4x4();
        clip.sample(time, boneMats);
        Matrix4x4[] finalMats = new Matrix4x4[skeleton.numBones];
        Matrix4x4[] normalMats = new Matrix4x4[skeleton.numBones];
        for (int i = 0; i < skeleton.numBones; i++) {
            finalMats[i] = new Matrix4x4();
            finalMats[i].mul(boneMats[i], skeleton.inverseBindPose[i]);
            normalMats[i] = inverseTranspose3x3(finalMats[i]);
        }
        for (int vi = 0; vi < mesh.vertices.length; vi++) {
            Vertex v = mesh.vertices[vi];
            float px = mesh.bindPositions[vi].x, py = mesh.bindPositions[vi].y, pz = mesh.bindPositions[vi].z, pw = mesh.bindPositions[vi].w;
            float nx = mesh.bindNormals[vi].x, ny = mesh.bindNormals[vi].y, nz = mesh.bindNormals[vi].z;
            v.position.x = 0; v.position.y = 0; v.position.z = 0; v.position.w = 0;
            v.normal.x = 0; v.normal.y = 0; v.normal.z = 0;
            for (int j = 0; j < 4; j++) {
                int bi = v.boneIndices[j];
                float bw = v.boneWeights[j];
                if (bi < 0 || bi >= skeleton.numBones || bw == 0) continue;
                Matrix4x4 m = finalMats[bi];
                v.position.x += (m.m00 * px + m.m01 * py + m.m02 * pz + m.m03 * pw) * bw;
                v.position.y += (m.m10 * px + m.m11 * py + m.m12 * pz + m.m13 * pw) * bw;
                v.position.z += (m.m20 * px + m.m21 * py + m.m22 * pz + m.m23 * pw) * bw;
                v.position.w += (m.m30 * px + m.m31 * py + m.m32 * pz + m.m33 * pw) * bw;
                Matrix4x4 nm = normalMats[bi];
                v.normal.x += (nm.m00 * nx + nm.m01 * ny + nm.m02 * nz) * bw;
                v.normal.y += (nm.m10 * nx + nm.m11 * ny + nm.m12 * nz) * bw;
                v.normal.z += (nm.m20 * nx + nm.m21 * ny + nm.m22 * nz) * bw;
            }
        }
    }

    public void renderAnimatedMesh(Mesh mesh, Matrix4x4 worldMatrix, Skeleton skeleton, AnimationClip clip, float time, Texture texture) {
        applySkinning(mesh, skeleton, clip, time);
        renderMesh(mesh, worldMatrix, texture, null);
    }

    public void renderMesh(Mesh mesh, Matrix4x4 worldMatrix) {
        renderMesh(mesh, worldMatrix, null, null);
    }

    public void renderMesh(Mesh mesh, Matrix4x4 worldMatrix, Texture texture) {
        renderMesh(mesh, worldMatrix, texture, null);
    }

    public void renderMesh(Mesh mesh, Matrix4x4 worldMatrix, Texture texture, Texture normalMap) {
        mvpMatrix.mul(viewProjMatrix, worldMatrix);
        
        for (int i = 0; i < mesh.numTriangles; i++) {
            int i0 = mesh.indices[i * 3];
            int i1 = mesh.indices[i * 3 + 1];
            int i2 = mesh.indices[i * 3 + 2];
            
            Vertex v0 = mesh.vertices[i0];
            Vertex v1 = mesh.vertices[i1];
            Vertex v2 = mesh.vertices[i2];
            
            Vertex tv0 = transformVertex(v0, mvpMatrix, worldMatrix);
            Vertex tv1 = transformVertex(v1, mvpMatrix, worldMatrix);
            Vertex tv2 = transformVertex(v2, mvpMatrix, worldMatrix);
            
            // Backface culling in world space
            if (backfaceCulling) {
                Vec3 normal = calculateNormal(tv0.position, tv1.position, tv2.position);
                if ((cullMode == CullMode.BACK && normal.z > 0) || (cullMode == CullMode.FRONT && normal.z < 0)) continue;
            }
            
            // Compute light-space positions for shadow mapping
            boolean doShadow = shadowMap != null;
            if (doShadow) {
                lightMVP.mul(lightVP, worldMatrix);
                Vec4 lp0 = mulVec4(v0.position, lightMVP);
                Vec4 lp1 = mulVec4(v1.position, lightMVP);
                Vec4 lp2 = mulVec4(v2.position, lightMVP);
                tv0.lpX = lp0.x; tv0.lpY = lp0.y; tv0.lpZ = lp0.z; tv0.lpW = lp0.w;
                tv1.lpX = lp1.x; tv1.lpY = lp1.y; tv1.lpZ = lp1.z; tv1.lpW = lp1.w;
                tv2.lpX = lp2.x; tv2.lpY = lp2.y; tv2.lpZ = lp2.z; tv2.lpW = lp2.w;
            }
            
            // Compute world-space positions for specular lighting
            {
                Vec4 w0 = mulVec4(v0.position, worldMatrix);
                Vec4 w1 = mulVec4(v1.position, worldMatrix);
                Vec4 w2 = mulVec4(v2.position, worldMatrix);
                float wDiv0 = 1f / Math.max(w0.w, 1e-10f);
                float wDiv1 = 1f / Math.max(w1.w, 1e-10f);
                float wDiv2 = 1f / Math.max(w2.w, 1e-10f);
                tv0.wx = w0.x * wDiv0; tv0.wy = w0.y * wDiv0; tv0.wz = w0.z * wDiv0;
                tv1.wx = w1.x * wDiv1; tv1.wy = w1.y * wDiv1; tv1.wz = w1.z * wDiv1;
                tv2.wx = w2.x * wDiv2; tv2.wy = w2.y * wDiv2; tv2.wz = w2.z * wDiv2;
                tv0.nnx = tv0.normal.x; tv0.nny = tv0.normal.y; tv0.nnz = tv0.normal.z;
                tv1.nnx = tv1.normal.x; tv1.nny = tv1.normal.y; tv1.nnz = tv1.normal.z;
                tv2.nnx = tv2.normal.x; tv2.nny = tv2.normal.y; tv2.nnz = tv2.normal.z;
            }
            
            // Clip against near plane
            Vertex[] clipped = clipTriangle(tv0, tv1, tv2);
            int numVerts = clipped.length;
            
            if (numVerts < 3) continue;
            
            // Per-vertex Blinn-Phong lighting (before perspective divide)
            for (int j = 0; j < numVerts; j++) {
                Vertex v = clipped[j];
                Vec3 normal = v.normal.clone();
                float diff = Math.max(0, normal.dot(lightDir));
                Vec3 viewDir = new Vec3(cameraPos.x - v.wx, cameraPos.y - v.wy, cameraPos.z - v.wz).normalize();
                Vec3 halfDir = lightDir.add(viewDir).normalize();
                float spec = (float)Math.pow(Math.max(0, normal.dot(halfDir)), shininess);
                float light = ambientIntensity + diffuseIntensity * diff + specularIntensity * spec;
                v.color = scaleColor(v.color, light);
            }
            
            // Perspective division and screen mapping
            for (int j = 0; j < numVerts; j++) {
                Vertex v = clipped[j];
                float invW = 1.0f / Math.max(v.position.w, 1e-10f);
                v.position.x *= invW;
                v.position.y *= invW;
                v.position.z *= invW;
                v.position.w = 1.0f;
                
                v.screenX = (int)((v.position.x * 0.5f + 0.5f) * width);
                v.screenY = (int)((-v.position.y * 0.5f + 0.5f) * height);
                v.depth = v.position.z;
                
                // Store inverse W for perspective correct interpolation
                v.invW = invW;
                if (texture != null) {
                    v.u = v.u * invW;
                    v.v = v.v * invW;
                }
                if (doShadow) {
                    v.lpX = v.lpX * invW;
                    v.lpY = v.lpY * invW;
                    v.lpZ = v.lpZ * invW;
                    v.lpW = v.lpW * invW;
                }
                v.wx = v.wx * invW;
                v.wy = v.wy * invW;
                v.wz = v.wz * invW;
                v.nnx = v.nnx * invW;
                v.nny = v.nny * invW;
                v.nnz = v.nnz * invW;
            }
            
            // Rasterize triangles
            if (renderMode != RenderMode.WIREFRAME) {
                if (useTileBased && threadPool != null) {
                    // Collect into tile batches for parallel processing
                    for (int j = 1; j < numVerts - 1; j++) {
                        int triIdx = statsTriangles++;
                        for (int ti = 0; ti < tileRegions.length; ti++) {
                            Vertex a = clipped[0], b = clipped[j], c = clipped[j + 1];
                            int minX = Math.max(tileRegions[ti].x, Math.max(0, Math.min(a.screenX, Math.min(b.screenX, c.screenX))));
                            int maxX = Math.min(tileRegions[ti].x + tileRegions[ti].w - 1, Math.min(width - 1, Math.max(a.screenX, Math.max(b.screenX, c.screenX))));
                            int minY = Math.max(tileRegions[ti].y, Math.max(0, Math.min(a.screenY, Math.min(b.screenY, c.screenY))));
                            int maxY = Math.min(tileRegions[ti].y + tileRegions[ti].h - 1, Math.min(height - 1, Math.max(a.screenY, Math.max(b.screenY, c.screenY))));
                            if (minX <= maxX && minY <= maxY) {
                                // Rasterize directly to this tile region
                                if (texture != null) rasterizeTexturedTriangleClipped(a, b, c, texture, normalMap, minX, maxX, minY, maxY);
                                else rasterizeTriangleClipped(a, b, c, minX, maxX, minY, maxY);
                            }
                        }
                    }
                } else {
                    for (int j = 1; j < numVerts - 1; j++) {
                        Vertex a = clipped[0], b = clipped[j], c = clipped[j + 1];
                        if (texture != null) rasterizeTexturedTriangle(a, b, c, texture, normalMap);
                        else rasterizeTriangle(a, b, c);
                    }
                }
            }
            
            // Wireframe overlay
            if (renderMode != RenderMode.SOLID && clipped.length >= 3) {
                Color wfColor = renderMode == RenderMode.WIREFRAME ? Color.WHITE : Color.YELLOW;
                for (int j = 1; j < numVerts - 1; j++) {
                    drawLine(clipped[0].screenX, clipped[0].screenY, clipped[j].screenX, clipped[j].screenY, wfColor);
                    drawLine(clipped[j].screenX, clipped[j].screenY, clipped[j+1].screenX, clipped[j+1].screenY, wfColor);
                }
                drawLine(clipped[numVerts-1].screenX, clipped[numVerts-1].screenY, clipped[0].screenX, clipped[0].screenY, wfColor);
            }
        }
    }
    
    private Color scaleColor(Color color, float factor) {
        int r = Math.min(255, Math.max(0, (int)(color.getRed() * factor)));
        int g = Math.min(255, Math.max(0, (int)(color.getGreen() * factor)));
        int b = Math.min(255, Math.max(0, (int)(color.getBlue() * factor)));
        return new Color(r, g, b);
    }
    
    private Vertex transformVertex(Vertex v, Matrix4x4 mat, Matrix4x4 world) {
        Vertex result = new Vertex();
        result.position.x = mat.m00 * v.position.x + mat.m01 * v.position.y + mat.m02 * v.position.z + mat.m03 * v.position.w;
        result.position.y = mat.m10 * v.position.x + mat.m11 * v.position.y + mat.m12 * v.position.z + mat.m13 * v.position.w;
        result.position.z = mat.m20 * v.position.x + mat.m21 * v.position.y + mat.m22 * v.position.z + mat.m23 * v.position.w;
        result.position.w = mat.m30 * v.position.x + mat.m31 * v.position.y + mat.m32 * v.position.z + mat.m33 * v.position.w;
        
        // Transform normal by world matrix (upper-left 3x3)
        result.normal.x = world.m00 * v.normal.x + world.m01 * v.normal.y + world.m02 * v.normal.z;
        result.normal.y = world.m10 * v.normal.x + world.m11 * v.normal.y + world.m12 * v.normal.z;
        result.normal.z = world.m20 * v.normal.x + world.m21 * v.normal.y + world.m22 * v.normal.z;
        result.normal.normalize();
        
        result.color = v.color;
        result.u = v.u;
        result.v = v.v;
        result.invW = v.invW;
        result.lpX = v.lpX;
        result.lpY = v.lpY;
        result.lpZ = v.lpZ;
        result.lpW = v.lpW;
        result.wx = v.wx;
        result.wy = v.wy;
        result.wz = v.wz;
        result.nnx = v.nnx;
        result.nny = v.nny;
        result.nnz = v.nnz;
        
        return result;
    }
    
    private Vec3 calculateNormal(Vec4 a, Vec4 b, Vec4 c) {
        float aw = a.w == 0 ? 1 : a.w, bw = b.w == 0 ? 1 : b.w, cw = c.w == 0 ? 1 : c.w;
        Vec3 ab = new Vec3(b.x / bw - a.x / aw, b.y / bw - a.y / aw, b.z / bw - a.z / aw);
        Vec3 ac = new Vec3(c.x / cw - a.x / aw, c.y / cw - a.y / aw, c.z / cw - a.z / aw);
        Vec3 normal = ab.cross(ac);
        normal.normalize();
        return normal;
    }
    
    private Vec3 calculateNormal(Vec3 a, Vec3 b, Vec3 c) {
        Vec3 ab = new Vec3(b.x - a.x, b.y - a.y, b.z - a.z);
        Vec3 ac = new Vec3(c.x - a.x, c.y - a.y, c.z - a.z);
        Vec3 normal = ab.cross(ac);
        normal.normalize();
        return normal;
    }
    
    private Vec3 applyMatrix3(Vec4 v, Matrix4x4 m) {
        return new Vec3(
            m.m00 * v.x + m.m01 * v.y + m.m02 * v.z,
            m.m10 * v.x + m.m11 * v.y + m.m12 * v.z,
            m.m20 * v.x + m.m21 * v.y + m.m22 * v.z
        );
    }
    
    private Matrix4x4 inverseTranspose3x3(Matrix4x4 m) {
        float a = m.m00, b = m.m01, c = m.m02;
        float d = m.m10, e = m.m11, f = m.m12;
        float g = m.m20, h = m.m21, i = m.m22;
        float det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (det == 0) return m;
        float invDet = 1f / det;
        Matrix4x4 r = new Matrix4x4();
        r.m00 = (e * i - f * h) * invDet;
        r.m01 = (c * h - b * i) * invDet;
        r.m02 = (b * f - c * e) * invDet;
        r.m10 = (f * g - d * i) * invDet;
        r.m11 = (a * i - c * g) * invDet;
        r.m12 = (c * d - a * f) * invDet;
        r.m20 = (d * h - e * g) * invDet;
        r.m21 = (b * g - a * h) * invDet;
        r.m22 = (a * e - b * d) * invDet;
        return r;
    }
    
    private Vec4 mulVec4(Vec4 v, Matrix4x4 m) {
        Vec4 r = new Vec4();
        r.x = m.m00 * v.x + m.m01 * v.y + m.m02 * v.z + m.m03 * v.w;
        r.y = m.m10 * v.x + m.m11 * v.y + m.m12 * v.z + m.m13 * v.w;
        r.z = m.m20 * v.x + m.m21 * v.y + m.m22 * v.z + m.m23 * v.w;
        r.w = m.m30 * v.x + m.m31 * v.y + m.m32 * v.z + m.m33 * v.w;
        return r;
    }
    
    private Vertex[] clipTriangle(Vertex v0, Vertex v1, Vertex v2) {
        float[][] planes = {
            { 1,  0,  0,  1}, {-1,  0,  0,  1},
            { 0,  1,  0,  1}, { 0, -1,  0,  1},
            { 0,  0,  1,  1}, { 0,  0, -1,  1}
        };
        java.util.List<Vertex> input = new java.util.ArrayList<>();
        input.add(v0); input.add(v1); input.add(v2);
        for (int p = 0; p < 6; p++) {
            float px = planes[p][0], py = planes[p][1], pz = planes[p][2], pw = planes[p][3];
            java.util.List<Vertex> output = new java.util.ArrayList<>();
            int n = input.size();
            if (n == 0) break;
            for (int i = 0; i < n; i++) {
                Vertex a = input.get(i);
                Vertex b = input.get((i + 1) % n);
                float dA = a.position.x * px + a.position.y * py + a.position.z * pz + a.position.w * pw;
                float dB = b.position.x * px + b.position.y * py + b.position.z * pz + b.position.w * pw;
                boolean insideA = dA >= 0;
                boolean insideB = dB >= 0;
                if (insideA) output.add(a);
                if (insideA != insideB) {
                    float t = dA / (dA - dB);
                    output.add(interpolateVertex(a, b, t));
                }
            }
            input = output;
        }
        return input.toArray(new Vertex[0]);
    }
    
    private Vertex interpolateVertex(Vertex a, Vertex b, float t) {
        Vertex result = new Vertex();
        result.position.x = a.position.x + (b.position.x - a.position.x) * t;
        result.position.y = a.position.y + (b.position.y - a.position.y) * t;
        result.position.z = a.position.z + (b.position.z - a.position.z) * t;
        result.position.w = a.position.w + (b.position.w - a.position.w) * t;
        
        result.normal.x = a.normal.x + (b.normal.x - a.normal.x) * t;
        result.normal.y = a.normal.y + (b.normal.y - a.normal.y) * t;
        result.normal.z = a.normal.z + (b.normal.z - a.normal.z) * t;
        result.normal.normalize();
        
        result.color = lerpColor(a.color, b.color, t);
        result.u = a.u + (b.u - a.u) * t;
        result.v = a.v + (b.v - a.v) * t;
        result.invW = a.invW + (b.invW - a.invW) * t;
        result.lpX = a.lpX + (b.lpX - a.lpX) * t;
        result.lpY = a.lpY + (b.lpY - a.lpY) * t;
        result.lpZ = a.lpZ + (b.lpZ - a.lpZ) * t;
        result.lpW = a.lpW + (b.lpW - a.lpW) * t;
        result.wx = a.wx + (b.wx - a.wx) * t;
        result.wy = a.wy + (b.wy - a.wy) * t;
        result.wz = a.wz + (b.wz - a.wz) * t;
        result.nnx = a.nnx + (b.nnx - a.nnx) * t;
        result.nny = a.nny + (b.nny - a.nny) * t;
        result.nnz = a.nnz + (b.nnz - a.nnz) * t;
        
        return result;
    }
    
    private void rasterizeTriangle(Vertex v0, Vertex v1, Vertex v2) {
        Vertex[] verts = {v0, v1, v2};
        sortByY(verts);
        
        // Degenerate triangle check
        if (verts[0].screenY == verts[2].screenY) return;
        
        int yStart = Math.max(0, verts[0].screenY);
        int yEnd = Math.min(height - 1, verts[2].screenY);
        
        for (int y = yStart; y <= yEnd; y++) {
            // Calculate scanline endpoints
            float t1 = (y - verts[0].screenY) / (float)(verts[2].screenY - verts[0].screenY);
            float t2;
            int rightA, rightB;
            
            int x1 = lerp(verts[0].screenX, verts[2].screenX, t1);
            int x2;
            if (y < verts[1].screenY) {
                float tTop = verts[1].screenY - verts[0].screenY;
                t2 = tTop != 0 ? (y - verts[0].screenY) / tTop : 0;
                x2 = lerp(verts[0].screenX, verts[1].screenX, t2);
                rightA = 0; rightB = 1;
            } else {
                float denom = verts[2].screenY - verts[1].screenY;
                t2 = denom != 0 ? (y - verts[1].screenY) / denom : 0;
                x2 = lerp(verts[1].screenX, verts[2].screenX, t2);
                rightA = 1; rightB = 2;
            }
            
            // Interpolate Z and color at endpoints (before potential swap)
            float z1 = lerp(verts[0].depth, verts[2].depth, t1);
            float z2 = lerp(verts[rightA].depth, verts[rightB].depth, t2);
            
            float lx1 = lerp(verts[0].lpX, verts[2].lpX, t1);
            float lx2 = lerp(verts[rightA].lpX, verts[rightB].lpX, t2);
            float ly1 = lerp(verts[0].lpY, verts[2].lpY, t1);
            float ly2 = lerp(verts[rightA].lpY, verts[rightB].lpY, t2);
            float lz1 = lerp(verts[0].lpZ, verts[2].lpZ, t1);
            float lz2 = lerp(verts[rightA].lpZ, verts[rightB].lpZ, t2);
            float lw1 = lerp(verts[0].lpW, verts[2].lpW, t1);
            float lw2 = lerp(verts[rightA].lpW, verts[rightB].lpW, t2);
            float iw1 = lerp(verts[0].invW, verts[2].invW, t1);
            float iw2 = lerp(verts[rightA].invW, verts[rightB].invW, t2);
            
            Color c1 = lerpColor(verts[0].color, verts[2].color, t1);
            Color c2 = lerpColor(verts[rightA].color, verts[rightB].color, t2);
            
            if (x1 > x2) {
                int tmp = x1; x1 = x2; x2 = tmp;
                float tf; tf = z1; z1 = z2; z2 = tf;
                tf = lx1; lx1 = lx2; lx2 = tf;
                tf = ly1; ly1 = ly2; ly2 = tf;
                tf = lz1; lz1 = lz2; lz2 = tf;
                tf = lw1; lw1 = lw2; lw2 = tf;
                tf = iw1; iw1 = iw2; iw2 = tf;
                Color tc = c1; c1 = c2; c2 = tc;
            }
            
            x1 = Math.max(0, x1);
            x2 = Math.min(width - 1, x2);
            
            if (x1 > x2) continue;
            
            boolean doShadow = shadowMap != null;
            
            for (int x = x1; x <= x2; x++) {
                float t = (x - x1) / (float)(x2 - x1 + 1);
                float depth = lerp(z1, z2, t);
                
                int index = y * width + x;
                if (depth < zBuffer[index]) {
                    zBuffer[index] = depth;
                    Color color = lerpColor(c1, c2, t);
                    
                    if (doShadow) {
                        float iw = lerp(iw1, iw2, t);
                        float lpx = lerp(lx1, lx2, t) / iw;
                        float lpy = lerp(ly1, ly2, t) / iw;
                        float lpz = lerp(lz1, lz2, t) / iw;
                        float lpw = lerp(lw1, lw2, t) / iw;
                        if (lpw > 0) {
                            float smU = (lpx / lpw) * 0.5f + 0.5f;
                            float smV = (-lpy / lpw) * 0.5f + 0.5f;
                            float smD = (lpz / lpw) * 0.5f + 0.5f;
                            if (smU >= 0 && smU < 1 && smV >= 0 && smV < 1) {
                                int smX = (int)(smU * shadowMap.size);
                                int smY = (int)(smV * shadowMap.size);
                                int sm = 0, total = 0;
                                int r = 1;
                                for (int dy = -r; dy <= r; dy++) {
                                    for (int dx = -r; dx <= r; dx++) {
                                        int sx = smX + dx, sy = smY + dy;
                                        if (sx >= 0 && sx < shadowMap.size && sy >= 0 && sy < shadowMap.size) {
                                            total++;
                                            if (smD <= shadowMap.depthBuffer[sy * shadowMap.size + sx] + shadowBias)
                                                sm++;
                                        }
                                    }
                                }
                                float shadow = (float)sm / total;
                                color = scaleColor(color, 1.0f - shadowIntensity * (1.0f - shadow));
                            }
                        }
                    }
                    
                    writeFragment(index, color, depth);
                }
            }
        }
    }
    
    private void rasterizeTexturedTriangle(Vertex vert0, Vertex vert1, Vertex vert2, Texture texture, Texture normalMap) {
        // Pre-compute tangent frame for normal mapping (world-space positions)
        boolean hasNormalMap = normalMap != null;
        Vec3 tangent = new Vec3(), bitangent = new Vec3();
        if (hasNormalMap) {
            float invW0 = Math.abs(vert0.invW) > 1e-10f ? vert0.invW : 1f;
            float invW1 = Math.abs(vert1.invW) > 1e-10f ? vert1.invW : 1f;
            float invW2 = Math.abs(vert2.invW) > 1e-10f ? vert2.invW : 1f;
            float p0x = vert0.wx / invW0, p0y = vert0.wy / invW0, p0z = vert0.wz / invW0;
            float p1x = vert1.wx / invW1, p1y = vert1.wy / invW1, p1z = vert1.wz / invW1;
            float p2x = vert2.wx / invW2, p2y = vert2.wy / invW2, p2z = vert2.wz / invW2;
            Vec3 edge1 = new Vec3(p1x - p0x, p1y - p0y, p1z - p0z);
            Vec3 edge2 = new Vec3(p2x - p0x, p2y - p0y, p2z - p0z);
            float du1 = vert1.u - vert0.u, dv1 = vert1.v - vert0.v;
            float du2 = vert2.u - vert0.u, dv2 = vert2.v - vert0.v;
            float det = du1 * dv2 - du2 * dv1;
            if (Math.abs(det) > 1e-6f) {
                float f = 1f / det;
                tangent.x = (edge1.x * dv2 - edge2.x * dv1) * f;
                tangent.y = (edge1.y * dv2 - edge2.y * dv1) * f;
                tangent.z = (edge1.z * dv2 - edge2.z * dv1) * f;
                tangent.normalize();
                bitangent.x = (edge2.x * du1 - edge1.x * du2) * f;
                bitangent.y = (edge2.y * du1 - edge1.y * du2) * f;
                bitangent.z = (edge2.z * du1 - edge1.z * du2) * f;
                bitangent.normalize();
            } else {
                hasNormalMap = false;
            }
        }
        Vertex[] verts = {vert0, vert1, vert2};
        sortByY(verts);
        
        // Degenerate triangle check
        if (verts[0].screenY == verts[2].screenY) return;
        
        int yStart = Math.max(0, verts[0].screenY);
        int yEnd = Math.min(height - 1, verts[2].screenY);
        
        for (int y = yStart; y <= yEnd; y++) {
            // Calculate scanline endpoints
            float t1 = (y - verts[0].screenY) / (float)(verts[2].screenY - verts[0].screenY);
            float t2;
            int rightA, rightB;
            
            int x1 = lerp(verts[0].screenX, verts[2].screenX, t1);
            int x2;
            if (y < verts[1].screenY) {
                float tTop = verts[1].screenY - verts[0].screenY;
                t2 = tTop != 0 ? (y - verts[0].screenY) / tTop : 0;
                x2 = lerp(verts[0].screenX, verts[1].screenX, t2);
                rightA = 0; rightB = 1;
            } else {
                float denom = verts[2].screenY - verts[1].screenY;
                t2 = denom != 0 ? (y - verts[1].screenY) / denom : 0;
                x2 = lerp(verts[1].screenX, verts[2].screenX, t2);
                rightA = 1; rightB = 2;
            }
            
            // Interpolate attributes at endpoints (before potential swap)
            float z1 = lerp(verts[0].depth, verts[2].depth, t1);
            float z2 = lerp(verts[rightA].depth, verts[rightB].depth, t2);
            
            float u1 = lerp(verts[0].u, verts[2].u, t1);
            float u2 = lerp(verts[rightA].u, verts[rightB].u, t2);
            float v1 = lerp(verts[0].v, verts[2].v, t1);
            float v2 = lerp(verts[rightA].v, verts[rightB].v, t2);
            float iw1 = lerp(verts[0].invW, verts[2].invW, t1);
            float iw2 = lerp(verts[rightA].invW, verts[rightB].invW, t2);
            
            float lx1 = lerp(verts[0].lpX, verts[2].lpX, t1);
            float lx2 = lerp(verts[rightA].lpX, verts[rightB].lpX, t2);
            float ly1 = lerp(verts[0].lpY, verts[2].lpY, t1);
            float ly2 = lerp(verts[rightA].lpY, verts[rightB].lpY, t2);
            float lz1 = lerp(verts[0].lpZ, verts[2].lpZ, t1);
            float lz2 = lerp(verts[rightA].lpZ, verts[rightB].lpZ, t2);
            float lw1 = lerp(verts[0].lpW, verts[2].lpW, t1);
            float lw2 = lerp(verts[rightA].lpW, verts[rightB].lpW, t2);
            
            float nnx1 = lerp(verts[0].nnx, verts[2].nnx, t1);
            float nnx2 = lerp(verts[rightA].nnx, verts[rightB].nnx, t2);
            float nny1 = lerp(verts[0].nny, verts[2].nny, t1);
            float nny2 = lerp(verts[rightA].nny, verts[rightB].nny, t2);
            float nnz1 = lerp(verts[0].nnz, verts[2].nnz, t1);
            float nnz2 = lerp(verts[rightA].nnz, verts[rightB].nnz, t2);
            
            float wx1 = lerp(verts[0].wx, verts[2].wx, t1);
            float wx2 = lerp(verts[rightA].wx, verts[rightB].wx, t2);
            float wy1 = lerp(verts[0].wy, verts[2].wy, t1);
            float wy2 = lerp(verts[rightA].wy, verts[rightB].wy, t2);
            float wz1 = lerp(verts[0].wz, verts[2].wz, t1);
            float wz2 = lerp(verts[rightA].wz, verts[rightB].wz, t2);
            
            Color c1 = lerpColor(verts[0].color, verts[2].color, t1);
            Color c2 = lerpColor(verts[rightA].color, verts[rightB].color, t2);
            
            if (x1 > x2) {
                int tmp = x1; x1 = x2; x2 = tmp;
                float tf; tf = z1; z1 = z2; z2 = tf;
                tf = u1; u1 = u2; u2 = tf;
                tf = v1; v1 = v2; v2 = tf;
                tf = iw1; iw1 = iw2; iw2 = tf;
                tf = lx1; lx1 = lx2; lx2 = tf;
                tf = ly1; ly1 = ly2; ly2 = tf;
                tf = lz1; lz1 = lz2; lz2 = tf;
                tf = lw1; lw1 = lw2; lw2 = tf;
                tf = nnx1; nnx1 = nnx2; nnx2 = tf;
                tf = nny1; nny1 = nny2; nny2 = tf;
                tf = nnz1; nnz1 = nnz2; nnz2 = tf;
                tf = wx1; wx1 = wx2; wx2 = tf;
                tf = wy1; wy1 = wy2; wy2 = tf;
                tf = wz1; wz1 = wz2; wz2 = tf;
                Color tc = c1; c1 = c2; c2 = tc;
            }
            
            x1 = Math.max(0, x1);
            x2 = Math.min(width - 1, x2);
            
            if (x1 > x2) continue;
            
            boolean doShadow = shadowMap != null;
            
            for (int x = x1; x <= x2; x++) {
                float t = (x - x1) / (float)(x2 - x1 + 1);
                float depth = lerp(z1, z2, t);
                
                int index = y * width + x;
                if (depth < zBuffer[index]) {
                    zBuffer[index] = depth;
                    
                    // Perspective correct texture mapping
                    float iw = lerp(iw1, iw2, t);
                    float u = lerp(u1, u2, t) / iw;
                    float v = lerp(v1, v2, t) / iw;
                    
                    // Wrap texture coordinates
                    u = ((u % 1.0f) + 1.0f) % 1.0f;
                    v = ((v % 1.0f) + 1.0f) % 1.0f;
                    
                    // Mipmap LOD selection
                    float lod = 0;
                    if (texture.mipmaps != null) {
                        float du = Math.abs(lerp(u1, u2, t + 0.01f) / iw - u) * texture.width;
                        float dv = Math.abs(lerp(v1, v2, t + 0.01f) / iw - v) * texture.height;
                        lod = (float)(Math.log(Math.max(du, dv)) / Math.log(2));
                    }
                    int texColor = texture.sample(u, v, lod);
                    Color color = lerpColor(c1, c2, t);
                    color = modulateColor(color, new Color(texColor));

                    // Per-pixel normal mapping and lighting
                    if (hasNormalMap) {
                        int nmX = (int)(u * normalMap.width);
                        int nmY = (int)(v * normalMap.height);
                        nmX = Math.min(normalMap.width - 1, Math.max(0, nmX));
                        nmY = Math.min(normalMap.height - 1, Math.max(0, nmY));
                        int nmColor = normalMap.pixels[nmY * normalMap.width + nmX];
                        float nx = ((nmColor >> 16) & 0xFF) / 127.5f - 1f;
                        float ny = ((nmColor >> 8) & 0xFF) / 127.5f - 1f;
                        float nz = (nmColor & 0xFF) / 127.5f - 1f;

                        float nnx = lerp(nnx1, nnx2, t) / iw;
                        float nny = lerp(nny1, nny2, t) / iw;
                        float nnz = lerp(nnz1, nnz2, t) / iw;
                        float invN = 1f / (float)Math.sqrt(nnx * nnx + nny * nny + nnz * nnz);
                        float wnx = nnx * invN, wny = nny * invN, wnz = nnz * invN;

                        // TBN transform
                        float ddx = nx * tangent.x + ny * bitangent.x + nz * wnx;
                        float ddy = nx * tangent.y + ny * bitangent.y + nz * wny;
                        float ddz = nx * tangent.z + ny * bitangent.z + nz * wnz;
                        float invD = 1f / (float)Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                        ddx *= invD; ddy *= invD; ddz *= invD;

                        float diff = ddx * lightDir.x + ddy * lightDir.y + ddz * lightDir.z;
                        diff = Math.max(0, diff);
                        float light = ambientIntensity + diffuseIntensity * diff;
                        light = Math.min(1, light);
                        int r = (int)(color.getRed() * light);
                        int g = (int)(color.getGreen() * light);
                        int b = (int)(color.getBlue() * light);
                        color = new Color(Math.min(255, r), Math.min(255, g), Math.min(255, b));
                    }

                    // Shadow map lookup
                    if (doShadow) {
                        float lpx = lerp(lx1, lx2, t) / iw;
                        float lpy = lerp(ly1, ly2, t) / iw;
                        float lpz = lerp(lz1, lz2, t) / iw;
                        float lpw = lerp(lw1, lw2, t) / iw;
                        if (lpw > 0) {
                            float ndcX = lpx / lpw;
                            float ndcY = lpy / lpw;
                            float ndcZ = lpz / lpw;
                            float smU = ndcX * 0.5f + 0.5f;
                            float smV = -ndcY * 0.5f + 0.5f;
                            float smD = ndcZ * 0.5f + 0.5f;
                            if (smU >= 0 && smU < 1 && smV >= 0 && smV < 1) {
                                int smX = (int)(smU * shadowMap.size);
                                int smY = (int)(smV * shadowMap.size);
                                int sm = 0, total = 0, r = 1;
                                for (int dy = -r; dy <= r; dy++) {
                                    for (int dx = -r; dx <= r; dx++) {
                                        int sx = smX + dx, sy = smY + dy;
                                        if (sx >= 0 && sx < shadowMap.size && sy >= 0 && sy < shadowMap.size) {
                                            total++;
                                            if (smD <= shadowMap.depthBuffer[sy * shadowMap.size + sx] + shadowBias)
                                                sm++;
                                        }
                                    }
                                }
                                float shadow = (float)sm / total;
                                color = scaleColor(color, 1.0f - shadowIntensity * (1.0f - shadow));
                            }
                        }
                    }
                    
                    writeFragment(index, color, depth);
                }
            }
        }
    }
    
    private void sortByY(Vertex[] verts) {
        for (int i = 0; i < 2; i++) {
            for (int j = i + 1; j < 3; j++) {
                if (verts[i].screenY > verts[j].screenY) {
                    Vertex temp = verts[i];
                    verts[i] = verts[j];
                    verts[j] = temp;
                }
            }
        }
    }

    public void renderSkybox(Texture texture) {
        renderSkybox(texture, 1f);
    }

    public void renderSkybox(Texture texture, float size) {
        Matrix4x4 invVP = viewProjMatrix.clone();
        invVP.invert();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                if (zBuffer[idx] == Float.MAX_VALUE) {
                    float ndcX = 2f * x / width - 1f;
                    float ndcY = 2f * y / height - 1f;
                    float dx = invVP.m00 * ndcX + invVP.m01 * ndcY + invVP.m02 * 1 + invVP.m03;
                    float dy = invVP.m10 * ndcX + invVP.m11 * ndcY + invVP.m12 * 1 + invVP.m13;
                    float dz = invVP.m20 * ndcX + invVP.m21 * ndcY + invVP.m22 * 1 + invVP.m23;
                    float len = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
                    dx /= len; dy /= len; dz /= len;
                    int tx = (int)(((dx * 0.5f + 0.5f) * texture.width) % texture.width);
                    int ty = (int)(((dy * 0.5f + 0.5f) * texture.height) % texture.height);
                    if (tx < 0) tx += texture.width;
                    if (ty < 0) ty += texture.height;
                    framebuffer[idx] = texture.pixels[ty * texture.width + tx];
                    zBuffer[idx] = 1.0f;
                }
            }
        }
    }
    
    private int lerp(int a, int b, float t) {
        return (int)(a + (b - a) * t);
    }
    
    private float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
    
    private static Color lerpColor(Color a, Color b, float t) {
        int r = (int)(a.getRed() + (b.getRed() - a.getRed()) * t);
        int g = (int)(a.getGreen() + (b.getGreen() - a.getGreen()) * t);
        int bl = (int)(a.getBlue() + (b.getBlue() - a.getBlue()) * t);
        return new Color(Math.min(255, Math.max(0, r)),
                        Math.min(255, Math.max(0, g)),
                        Math.min(255, Math.max(0, bl)));
    }
    
    private static int lerpRGB(int c0, int c1, float t) {
        int r0 = (c0 >> 16) & 0xFF, g0 = (c0 >> 8) & 0xFF, b0 = c0 & 0xFF;
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int r = (int)(r0 + (r1 - r0) * t);
        int g = (int)(g0 + (g1 - g0) * t);
        int b = (int)(b0 + (b1 - b0) * t);
        return (Math.min(255, Math.max(0, r)) << 16) | (Math.min(255, Math.max(0, g)) << 8) | Math.min(255, Math.max(0, b));
    }
    
    private Color modulateColor(Color a, Color b) {
        int r = (a.getRed() * b.getRed()) / 255;
        int g = (a.getGreen() * b.getGreen()) / 255;
        int bl = (a.getBlue() * b.getBlue()) / 255;
        return new Color(r, g, bl);
    }

    private void writeFragment(int index, Color color, float depth) {
        if (fogEnabled) {
            float zLinear = (2f * nearClip * farClip) / (farClip + nearClip - depth * (farClip - nearClip));
            float fogFactor = Math.max(0, Math.min(1, (zLinear - fogNear) / (fogFar - fogNear)));
            int r = (int)(color.getRed()   * (1 - fogFactor) + fogColor.getRed()   * fogFactor);
            int g = (int)(color.getGreen() * (1 - fogFactor) + fogColor.getGreen() * fogFactor);
            int b = (int)(color.getBlue()  * (1 - fogFactor) + fogColor.getBlue()  * fogFactor);
            color = new Color(Math.min(255, r), Math.min(255, g), Math.min(255, b));
        }
        if (blendMode == BlendMode.ALPHA) {
            int src = color.getRGB();
            int dst = framebuffer[index];
            int sa = (src >> 24) & 0xFF;
            int sr = (src >> 16) & 0xFF, sg = (src >> 8) & 0xFF, sb = src & 0xFF;
            int dr = (dst >> 16) & 0xFF, dg = (dst >> 8) & 0xFF, db = dst & 0xFF;
            float a = sa / 255f;
            framebuffer[index] = ((int)(sr * a + dr * (1 - a)) << 16)
                               | ((int)(sg * a + dg * (1 - a)) << 8)
                               |  (int)(sb * a + db * (1 - a));
        } else if (blendMode == BlendMode.ADDITIVE) {
            int src = color.getRGB();
            int dst = framebuffer[index];
            int sr = (src >> 16) & 0xFF, sg = (src >> 8) & 0xFF, sb = src & 0xFF;
            int dr = (dst >> 16) & 0xFF, dg = (dst >> 8) & 0xFF, db = dst & 0xFF;
            framebuffer[index] = (Math.min(255, sr + dr) << 16)
                               | (Math.min(255, sg + dg) << 8)
                               |  Math.min(255, sb + db);
        } else {
            int rgb = color.getRGB();
            if (gammaCorrection) {
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                r = (int)(Math.pow(r / 255f, 1f / 2.2f) * 255);
                g = (int)(Math.pow(g / 255f, 1f / 2.2f) * 255);
                b = (int)(Math.pow(b / 255f, 1f / 2.2f) * 255);
                rgb = (r << 16) | (g << 8) | b;
            }
            framebuffer[index] = rgb;
        }
    }
    
    public void drawLine(int x0, int y0, int x1, int y1, Color color) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int rgb = color.getRGB();
        while (true) {
            if (x0 >= 0 && x0 < width && y0 >= 0 && y0 < height)
                framebuffer[y0 * width + x0] = rgb;
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    public void drawText(String text, int x, int y, Color color) {
        // Create a temporary image for the text
        BufferedImage textImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = textImage.createGraphics();
        g.setColor(color);
        g.setFont(new Font("Arial", Font.PLAIN, 14));
        g.drawString(text, x, y);
        g.dispose();
        
        // Blend text onto framebuffer
        int[] textPixels = ((DataBufferInt) textImage.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < width * height; i++) {
            if (textPixels[i] != 0) {
                framebuffer[i] = textPixels[i];
            }
        }
    }
    
    public int[] getPixels() {
        return framebuffer;
    }
    
    public BufferedImage getImage() {
        image.setRGB(0, 0, width, height, framebuffer, 0, width);
        return image;
    }
    
    public void setBackfaceCulling(boolean cull) {
        this.backfaceCulling = cull;
    }
    
    public void setLightDirection(Vec3 dir) {
        this.lightDir = dir.normalize();
    }
    
    public void setLighting(float ambient, float diffuse) {
        this.ambientIntensity = ambient;
        this.diffuseIntensity = diffuse;
    }
    
    // ======================== INNER CLASSES ========================
    
    public static class Vec3 {
        public float x, y, z;
        
        public Vec3() { this(0, 0, 0); }
        public Vec3(float x, float y, float z) {
            this.x = x; this.y = y; this.z = z;
        }
        
        public Vec3 add(Vec3 other) {
            return new Vec3(x + other.x, y + other.y, z + other.z);
        }
        
        public Vec3 sub(Vec3 other) {
            return new Vec3(x - other.x, y - other.y, z - other.z);
        }
        
        public Vec3 scale(float s) {
            return new Vec3(x * s, y * s, z * s);
        }
        
        public float dot(Vec3 other) {
            return x * other.x + y * other.y + z * other.z;
        }
        
        public Vec3 cross(Vec3 other) {
            return new Vec3(
                y * other.z - z * other.y,
                z * other.x - x * other.z,
                x * other.y - y * other.x
            );
        }
        
        public float length() {
            return (float)Math.sqrt(x*x + y*y + z*z);
        }
        
        public Vec3 normalize() {
            float len = length();
            if (len == 0) return new Vec3();
            return new Vec3(x / len, y / len, z / len);
        }
        
        public Vec3 clone() {
            return new Vec3(x, y, z);
        }
    }
    
    public static class Vec4 {
        public float x, y, z, w;
        
        public Vec4() { this(0, 0, 0, 1); }
        public Vec4(float x, float y, float z, float w) {
            this.x = x; this.y = y; this.z = z; this.w = w;
        }
        
        public Vec4 clone() {
            return new Vec4(x, y, z, w);
        }
    }
    
    public static class Matrix4x4 {
        public float m00, m01, m02, m03;
        public float m10, m11, m12, m13;
        public float m20, m21, m22, m23;
        public float m30, m31, m32, m33;
        
        public Matrix4x4() {
            identity();
        }
        
        public void identity() {
            m00 = 1; m01 = 0; m02 = 0; m03 = 0;
            m10 = 0; m11 = 1; m12 = 0; m13 = 0;
            m20 = 0; m21 = 0; m22 = 1; m23 = 0;
            m30 = 0; m31 = 0; m32 = 0; m33 = 1;
        }
        
        public void perspective(float fovDeg, float aspect, float near, float far) {
            float f = 1.0f / (float)Math.tan(Math.toRadians(fovDeg) / 2.0f);
            m00 = f / aspect; m01 = 0; m02 = 0; m03 = 0;
            m10 = 0; m11 = f; m12 = 0; m13 = 0;
            m20 = 0; m21 = 0; m22 = (far + near) / (near - far); m23 = (2 * far * near) / (near - far);
            m30 = 0; m31 = 0; m32 = -1; m33 = 0;
        }
        
        public void lookAt(Vec3 eye, Vec3 target, Vec3 up) {
            Vec3 z = eye.sub(target).normalize();
            Vec3 x = up.cross(z).normalize();
            Vec3 y = z.cross(x).normalize();
            
            m00 = x.x; m01 = x.y; m02 = x.z; m03 = -x.dot(eye);
            m10 = y.x; m11 = y.y; m12 = y.z; m13 = -y.dot(eye);
            m20 = z.x; m21 = z.y; m22 = z.z; m23 = -z.dot(eye);
            m30 = 0; m31 = 0; m32 = 0; m33 = 1;
        }
        
        public void translate(float x, float y, float z) {
            Matrix4x4 t = new Matrix4x4();
            t.m03 = x; t.m13 = y; t.m23 = z;
            mul(t, this);
        }
        
        public void rotateX(float angle) {
            float c = (float)Math.cos(angle);
            float s = (float)Math.sin(angle);
            Matrix4x4 r = new Matrix4x4();
            r.m11 = c; r.m12 = -s; r.m21 = s; r.m22 = c;
            mul(r, this);
        }
        
        public void rotateY(float angle) {
            float c = (float)Math.cos(angle);
            float s = (float)Math.sin(angle);
            Matrix4x4 r = new Matrix4x4();
            r.m00 = c; r.m02 = s; r.m20 = -s; r.m22 = c;
            mul(r, this);
        }
        
        public void rotateZ(float angle) {
            float c = (float)Math.cos(angle);
            float s = (float)Math.sin(angle);
            Matrix4x4 r = new Matrix4x4();
            r.m00 = c; r.m01 = -s; r.m10 = s; r.m11 = c;
            mul(r, this);
        }
        
        public void scale(float x, float y, float z) {
            Matrix4x4 s = new Matrix4x4();
            s.m00 = x; s.m11 = y; s.m22 = z;
            mul(s, this);
        }
        
        public void mul(Matrix4x4 a, Matrix4x4 b) {
            Matrix4x4 result = new Matrix4x4();
            result.m00 = a.m00*b.m00 + a.m01*b.m10 + a.m02*b.m20 + a.m03*b.m30;
            result.m01 = a.m00*b.m01 + a.m01*b.m11 + a.m02*b.m21 + a.m03*b.m31;
            result.m02 = a.m00*b.m02 + a.m01*b.m12 + a.m02*b.m22 + a.m03*b.m32;
            result.m03 = a.m00*b.m03 + a.m01*b.m13 + a.m02*b.m23 + a.m03*b.m33;
            result.m10 = a.m10*b.m00 + a.m11*b.m10 + a.m12*b.m20 + a.m13*b.m30;
            result.m11 = a.m10*b.m01 + a.m11*b.m11 + a.m12*b.m21 + a.m13*b.m31;
            result.m12 = a.m10*b.m02 + a.m11*b.m12 + a.m12*b.m22 + a.m13*b.m32;
            result.m13 = a.m10*b.m03 + a.m11*b.m13 + a.m12*b.m23 + a.m13*b.m33;
            result.m20 = a.m20*b.m00 + a.m21*b.m10 + a.m22*b.m20 + a.m23*b.m30;
            result.m21 = a.m20*b.m01 + a.m21*b.m11 + a.m22*b.m21 + a.m23*b.m31;
            result.m22 = a.m20*b.m02 + a.m21*b.m12 + a.m22*b.m22 + a.m23*b.m32;
            result.m23 = a.m20*b.m03 + a.m21*b.m13 + a.m22*b.m23 + a.m23*b.m33;
            result.m30 = a.m30*b.m00 + a.m31*b.m10 + a.m32*b.m20 + a.m33*b.m30;
            result.m31 = a.m30*b.m01 + a.m31*b.m11 + a.m32*b.m21 + a.m33*b.m31;
            result.m32 = a.m30*b.m02 + a.m31*b.m12 + a.m32*b.m22 + a.m33*b.m32;
            result.m33 = a.m30*b.m03 + a.m31*b.m13 + a.m32*b.m23 + a.m33*b.m33;
            
            this.m00 = result.m00; this.m01 = result.m01; this.m02 = result.m02; this.m03 = result.m03;
            this.m10 = result.m10; this.m11 = result.m11; this.m12 = result.m12; this.m13 = result.m13;
            this.m20 = result.m20; this.m21 = result.m21; this.m22 = result.m22; this.m23 = result.m23;
            this.m30 = result.m30; this.m31 = result.m31; this.m32 = result.m32; this.m33 = result.m33;
        }
        
        public float[] getArray() {
            return new float[] {
                m00, m01, m02, m03,
                m10, m11, m12, m13,
                m20, m21, m22, m23,
                m30, m31, m32, m33
            };
        }
        
        public Matrix4x4 clone() {
            Matrix4x4 result = new Matrix4x4();
            result.m00 = this.m00; result.m01 = this.m01; result.m02 = this.m02; result.m03 = this.m03;
            result.m10 = this.m10; result.m11 = this.m11; result.m12 = this.m12; result.m13 = this.m13;
            result.m20 = this.m20; result.m21 = this.m21; result.m22 = this.m22; result.m23 = this.m23;
            result.m30 = this.m30; result.m31 = this.m31; result.m32 = this.m32; result.m33 = this.m33;
            return result;
        }

        public void invert() {
            float a00 = m00, a01 = m01, a02 = m02, a03 = m03;
            float a10 = m10, a11 = m11, a12 = m12, a13 = m13;
            float a20 = m20, a21 = m21, a22 = m22, a23 = m23;
            float a30 = m30, a31 = m31, a32 = m32, a33 = m33;
            float b00 = a00*a11 - a01*a10, b01 = a00*a12 - a02*a10, b02 = a00*a13 - a03*a10;
            float b03 = a01*a12 - a02*a11, b04 = a01*a13 - a03*a11, b05 = a02*a13 - a03*a12;
            float b06 = a20*a31 - a21*a30, b07 = a20*a32 - a22*a30, b08 = a20*a33 - a23*a30;
            float b09 = a21*a32 - a22*a31, b10 = a21*a33 - a23*a31, b11 = a22*a33 - a23*a32;
            float det = b00*b11 - b01*b10 + b02*b09 + b03*b08 - b04*b07 + b05*b06;
            if (Math.abs(det) < 1e-10f) return;
            float inv = 1f / det;
            m00 = ( a11*b11 - a12*b10 + a13*b09) * inv;
            m01 = (-a01*b11 + a02*b10 - a03*b09) * inv;
            m02 = ( a31*b05 - a32*b04 + a33*b03) * inv;
            m03 = (-a21*b05 + a22*b04 - a23*b03) * inv;
            m10 = (-a10*b11 + a12*b08 - a13*b07) * inv;
            m11 = ( a00*b11 - a02*b08 + a03*b07) * inv;
            m12 = (-a30*b05 + a32*b02 - a33*b01) * inv;
            m13 = ( a20*b05 - a22*b02 + a23*b01) * inv;
            m20 = ( a10*b10 - a11*b08 + a13*b06) * inv;
            m21 = (-a00*b10 + a01*b08 - a03*b06) * inv;
            m22 = ( a30*b04 - a31*b02 + a33*b00) * inv;
            m23 = (-a20*b04 + a21*b02 - a23*b00) * inv;
            m30 = (-a10*b09 + a11*b07 - a12*b06) * inv;
            m31 = ( a00*b09 - a01*b07 + a02*b06) * inv;
            m32 = (-a30*b03 + a31*b01 - a32*b00) * inv;
            m33 = ( a20*b03 - a21*b01 + a22*b00) * inv;
        }
    }
    
    public static class Skeleton {
        public int numBones;
        public Matrix4x4[] boneTransforms;
        public Matrix4x4[] inverseBindPose;
        public int[] parentIndices;

        public Skeleton(int numBones) {
            this.numBones = numBones;
            boneTransforms = new Matrix4x4[numBones];
            inverseBindPose = new Matrix4x4[numBones];
            parentIndices = new int[numBones];
            for (int i = 0; i < numBones; i++) {
                boneTransforms[i] = new Matrix4x4();
                inverseBindPose[i] = new Matrix4x4();
                parentIndices[i] = -1;
            }
        }
    }

    public static class AnimationKeyframe {
        public float time;
        public Vec3 translation = new Vec3();
        public Vec3 rotation = new Vec3();
        public Vec3 scale = new Vec3(1, 1, 1);
    }

    public static class AnimationClip {
        public String name;
        public float duration;
        public java.util.List<AnimationKeyframe>[] keyframes;

        @SuppressWarnings("unchecked")
        public AnimationClip(int numBones, float duration) {
            this.duration = duration;
            keyframes = (java.util.List<AnimationKeyframe>[]) new java.util.List[numBones];
            for (int i = 0; i < numBones; i++) keyframes[i] = new java.util.ArrayList<>();
        }

        public void sample(float time, Matrix4x4[] outTransforms) {
            for (int b = 0; b < keyframes.length; b++) {
                java.util.List<AnimationKeyframe> kfs = keyframes[b];
                if (kfs.isEmpty()) { outTransforms[b].identity(); continue; }
                float t = time % duration;
                AnimationKeyframe prev = kfs.get(0), next = kfs.get(0);
                for (int i = 0; i < kfs.size(); i++) {
                    if (kfs.get(i).time <= t) prev = kfs.get(i);
                    if (kfs.get(i).time >= t) { next = kfs.get(i); break; }
                }
                float frac = prev == next ? 0 : (t - prev.time) / (next.time - prev.time);
                float tx = prev.translation.x + (next.translation.x - prev.translation.x) * frac;
                float ty = prev.translation.y + (next.translation.y - prev.translation.y) * frac;
                float tz = prev.translation.z + (next.translation.z - prev.translation.z) * frac;
                float rx = prev.rotation.x + (next.rotation.x - prev.rotation.x) * frac;
                float ry = prev.rotation.y + (next.rotation.y - prev.rotation.y) * frac;
                float rz = prev.rotation.z + (next.rotation.z - prev.rotation.z) * frac;
                float sx = prev.scale.x + (next.scale.x - prev.scale.x) * frac;
                float sy = prev.scale.y + (next.scale.y - prev.scale.y) * frac;
                float sz = prev.scale.z + (next.scale.z - prev.scale.z) * frac;
                outTransforms[b].identity();
                outTransforms[b].translate(tx, ty, tz);
                outTransforms[b].rotateX(rx); outTransforms[b].rotateY(ry); outTransforms[b].rotateZ(rz);
                outTransforms[b].scale(sx, sy, sz);
            }
        }
    }

    public static class Vertex {
        public Vec4 position = new Vec4();
        public Vec3 normal = new Vec3(0, 0, 1);
        public Color color = Color.WHITE;
        public float u = 0, v = 0;
        public float invW = 1;
        public int screenX, screenY;
        public float depth;
        public float lpX = 0, lpY = 0, lpZ = 0, lpW = 1;
        public float wx = 0, wy = 0, wz = 0;
        public float nnx = 0, nny = 0, nnz = 0;
        public int[] boneIndices = new int[4];
        public float[] boneWeights = new float[4];
        
        public Vertex() {}
        
        public Vertex(Vec3 pos, Vec3 normal, Color color, float u, float v) {
            this.position = new Vec4(pos.x, pos.y, pos.z, 1);
            this.normal = normal;
            this.color = color;
            this.u = u;
            this.v = v;
        }
    }

    public static class Material {
        public String name;
        public Color diffuse = new Color(180, 180, 180);
        public Color specular = new Color(255, 255, 255);
        public float shininess = 32f;
        public String texturePath;
        public Texture texture;
    }

    public static class Particle {
        public Vec3 position = new Vec3();
        public Vec3 velocity = new Vec3();
        public Color color = Color.WHITE;
        public float size = 1f;
        public float life = 1f;
        public float maxLife = 1f;
    }

    public static class ParticleEmitter {
        public Vec3 position = new Vec3();
        public java.util.List<Particle> particles = new java.util.ArrayList<>();
        public float spawnRate = 10f;
        public float spawnTimer = 0;
        public float particleLife = 2f;
        public float particleSize = 0.1f;
        public Color startColor = Color.WHITE;
        public Color endColor = new Color(100, 100, 100, 0);
        public float speed = 1f;
        public float spread = 1f;
        public boolean active = true;

        public void update(float dt) {
            spawnTimer += dt;
            float interval = 1f / spawnRate;
            while (spawnTimer >= interval && active) {
                spawnTimer -= interval;
                Particle p = new Particle();
                p.position = new Vec3(position.x, position.y, position.z);
                p.velocity = new Vec3(
                    (float)(Math.random() - 0.5) * spread,
                    (float)(Math.random() - 0.5) * spread,
                    (float)(Math.random() - 0.5) * spread
                );
                p.velocity.normalize();
                p.velocity.x *= speed; p.velocity.y *= speed; p.velocity.z *= speed;
                p.color = startColor;
                p.size = particleSize;
                p.life = particleLife;
                p.maxLife = particleLife;
                particles.add(p);
            }
            for (int i = particles.size() - 1; i >= 0; i--) {
                Particle p = particles.get(i);
                p.position.x += p.velocity.x * dt;
                p.position.y += p.velocity.y * dt;
                p.position.z += p.velocity.z * dt;
                p.life -= dt;
                float t = Math.max(0, p.life / p.maxLife);
                p.color = lerpColor(endColor, startColor, t);
                p.size = particleSize * (0.5f + 0.5f * t);
                if (p.life <= 0) particles.remove(i);
            }
        }
    }

    public static class Mesh {
        public Vertex[] vertices;
        public int[] indices;
        public int numTriangles;
        public java.util.Map<String, Material> materials = new java.util.HashMap<>();
        public Vec4[] bindPositions;
        public Vec3[] bindNormals;
        
        public Mesh(Vertex[] vertices, int[] indices) {
            this.vertices = vertices;
            this.indices = indices;
            this.numTriangles = indices.length / 3;
        }
        
        public void saveBindPose() {
            if (bindPositions != null) return;
            bindPositions = new Vec4[vertices.length];
            bindNormals = new Vec3[vertices.length];
            for (int i = 0; i < vertices.length; i++) {
                bindPositions[i] = new Vec4(vertices[i].position.x, vertices[i].position.y, vertices[i].position.z, vertices[i].position.w);
                bindNormals[i] = new Vec3(vertices[i].normal.x, vertices[i].normal.y, vertices[i].normal.z);
            }
        }
        
        public static Mesh createTexturedCube(float size, Texture texture) {
            float h = size / 2;
            Vec3[] positions = {
                new Vec3(-h, -h, -h), new Vec3( h, -h, -h), new Vec3( h,  h, -h), new Vec3(-h,  h, -h),
                new Vec3(-h, -h,  h), new Vec3( h, -h,  h), new Vec3( h,  h,  h), new Vec3(-h,  h,  h)
            };
            
            Vec3[] normals = {
                new Vec3(0, 0, -1), new Vec3(0, 0, 1),
                new Vec3(-1, 0, 0), new Vec3(1, 0, 0),
                new Vec3(0, -1, 0), new Vec3(0, 1, 0)
            };
            
            int[][] faces = {
                {0,1,2,3}, {5,4,7,6},
                {4,0,3,7}, {1,5,6,2},
                {4,5,1,0}, {3,2,6,7}
            };
            
            Vertex[] verts = new Vertex[24];
            int idx = 0;
            for (int f = 0; f < 6; f++) {
                // Different texture coordinates per face for better visualization
                float[][] texCoords = {
                    {0,0, 1,0, 1,1, 0,1},
                    {0,0, 1,0, 1,1, 0,1},
                    {0,0, 1,0, 1,1, 0,1},
                    {0,0, 1,0, 1,1, 0,1},
                    {0,0, 1,0, 1,1, 0,1},
                    {0,0, 1,0, 1,1, 0,1}
                };
                
                for (int i = 0; i < 4; i++) {
                    Vec3 pos = positions[faces[f][i]];
                    Vec3 normal = normals[f];
                    float u = texCoords[f][i * 2];
                    float v = texCoords[f][i * 2 + 1];
                    verts[idx++] = new Vertex(pos, normal, Color.WHITE, u, v);
                }
            }
            
            int[] indices = new int[36];
            for (int f = 0; f < 6; f++) {
                int base = f * 4;
                indices[f * 6 + 0] = base;
                indices[f * 6 + 1] = base + 1;
                indices[f * 6 + 2] = base + 2;
                indices[f * 6 + 3] = base;
                indices[f * 6 + 4] = base + 2;
                indices[f * 6 + 5] = base + 3;
            }
            
            return new Mesh(verts, indices);
        }
        
        public static Mesh createCube(float size, Color color) {
            float h = size / 2;
            Vec3[] positions = {
                new Vec3(-h, -h, -h), new Vec3( h, -h, -h), new Vec3( h,  h, -h), new Vec3(-h,  h, -h),
                new Vec3(-h, -h,  h), new Vec3( h, -h,  h), new Vec3( h,  h,  h), new Vec3(-h,  h,  h)
            };
            
            Vec3[] normals = {
                new Vec3(0, 0, -1), new Vec3(0, 0, 1),
                new Vec3(-1, 0, 0), new Vec3(1, 0, 0),
                new Vec3(0, -1, 0), new Vec3(0, 1, 0)
            };
            
            int[][] faces = {
                {0,1,2,3}, {5,4,7,6},
                {4,0,3,7}, {1,5,6,2},
                {4,5,1,0}, {3,2,6,7}
            };
            
            Vertex[] verts = new Vertex[24];
            int idx = 0;
            for (int f = 0; f < 6; f++) {
                for (int i = 0; i < 4; i++) {
                    Vec3 pos = positions[faces[f][i]];
                    Vec3 normal = normals[f];
                    verts[idx++] = new Vertex(pos, normal, color, 0, 0);
                }
            }
            
            int[] indices = new int[36];
            for (int f = 0; f < 6; f++) {
                int base = f * 4;
                indices[f * 6 + 0] = base;
                indices[f * 6 + 1] = base + 1;
                indices[f * 6 + 2] = base + 2;
                indices[f * 6 + 3] = base;
                indices[f * 6 + 4] = base + 2;
                indices[f * 6 + 5] = base + 3;
            }
            
            return new Mesh(verts, indices);
        }

        public static Map<String, Material> loadMTL(String filename) throws IOException {
            Map<String, Material> materials = new java.util.HashMap<>();
            java.util.List<String> lines = Files.readAllLines(Paths.get(filename));
            Material current = null;
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+");
                switch (parts[0]) {
                    case "newmtl":
                        current = new Material();
                        current.name = parts[1];
                        materials.put(current.name, current);
                        break;
                    case "Kd":
                        if (current != null) {
                            int r = (int)(Float.parseFloat(parts[1]) * 255);
                            int g = (int)(Float.parseFloat(parts[2]) * 255);
                            int b = (int)(Float.parseFloat(parts[3]) * 255);
                            current.diffuse = new Color(Math.min(255, r), Math.min(255, g), Math.min(255, b));
                        }
                        break;
                    case "Ks":
                        if (current != null) {
                            int r = (int)(Float.parseFloat(parts[1]) * 255);
                            int g = (int)(Float.parseFloat(parts[2]) * 255);
                            int b = (int)(Float.parseFloat(parts[3]) * 255);
                            current.specular = new Color(Math.min(255, r), Math.min(255, g), Math.min(255, b));
                        }
                        break;
                    case "Ns":
                        if (current != null) current.shininess = Float.parseFloat(parts[1]);
                        break;
                    case "map_Kd":
                        if (current != null) {
                            String texFile = parts.length > 1 ? String.join(" ", Arrays.copyOfRange(parts, 1, parts.length)) : "";
                            if (!texFile.isEmpty()) {
                                java.nio.file.Path mtlDir = Paths.get(filename).getParent();
                                current.texturePath = mtlDir != null ? mtlDir.resolve(texFile).toString() : texFile;
                                try {
                                    current.texture = Texture.load(current.texturePath);
                                } catch (Exception e) {
                                    current.texture = null;
                                }
                            }
                        }
                        break;
                }
            }
            return materials;
        }

        public static Mesh loadOBJ(String filename) throws IOException {
            java.util.List<String> lines = Files.readAllLines(Paths.get(filename));
            java.util.List<Vec3> positions = new java.util.ArrayList<>();
            java.util.List<Vec3> normals = new java.util.ArrayList<>();
            java.util.List<float[]> texCoords = new java.util.ArrayList<>();
            java.util.List<int[]> faceIndices = new java.util.ArrayList<>();
            java.util.List<Integer> faceVertCounts = new java.util.ArrayList<>();
            java.util.List<Color> faceColors = new java.util.ArrayList<>();

            positions.add(null); // 1-indexed
            normals.add(null);
            texCoords.add(null);

            Mesh mesh = new Mesh(new Vertex[0], new int[0]);
            Color currentColor = Color.WHITE;

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+");
                switch (parts[0]) {
                    case "v":
                        positions.add(new Vec3(
                            Float.parseFloat(parts[1]),
                            Float.parseFloat(parts[2]),
                            Float.parseFloat(parts[3])
                        ));
                        break;
                    case "vn":
                        normals.add(new Vec3(
                            Float.parseFloat(parts[1]),
                            Float.parseFloat(parts[2]),
                            Float.parseFloat(parts[3])
                        ));
                        break;
                    case "vt":
                        texCoords.add(new float[]{
                            Float.parseFloat(parts[1]),
                            Float.parseFloat(parts[2])
                        });
                        break;
                    case "f": {
                        int[] verts = new int[(parts.length - 1) * 3];
                        int idx = 0;
                        for (int i = 1; i < parts.length; i++) {
                            String[] v = parts[i].split("/");
                            verts[idx++] = Integer.parseInt(v[0]);
                            verts[idx++] = v.length > 1 && !v[1].isEmpty() ? Integer.parseInt(v[1]) : 0;
                            verts[idx++] = v.length > 2 && !v[2].isEmpty() ? Integer.parseInt(v[2]) : 0;
                        }
                        faceIndices.add(verts);
                        faceVertCounts.add(parts.length - 1);
                        faceColors.add(currentColor);
                        break;
                    }
                    case "mtllib": {
                        java.nio.file.Path objDir = Paths.get(filename).getParent();
                        String mtlPath = objDir != null ? objDir.resolve(parts[1]).toString() : parts[1];
                        try {
                            Map<String, Material> loaded = loadMTL(mtlPath);
                            mesh.materials.putAll(loaded);
                        } catch (Exception ignored) {}
                        break;
                    }
                    case "usemtl": {
                        Material mat = mesh.materials.get(parts[1]);
                        currentColor = mat != null ? mat.diffuse : Color.WHITE;
                        break;
                    }
                }
            }

            // Build vertex array
            java.util.List<Vertex> vertList = new java.util.ArrayList<>();
            java.util.List<Integer> idxList = new java.util.ArrayList<>();
            Map<String, Integer> vertMap = new HashMap<>();

            for (int fi = 0; fi < faceIndices.size(); fi++) {
                int[] face = faceIndices.get(fi);
                int numVerts = faceVertCounts.get(fi);
                Color faceColor = faceColors.get(fi);
                for (int i = 1; i < numVerts - 1; i++) {
                    int[] triVerts = {0, i, i + 1};
                    for (int t = 0; t < 3; t++) {
                        int vi = triVerts[t];
                        int pi = face[vi * 3];
                        int ti = face[vi * 3 + 1];
                        int ni = face[vi * 3 + 2];
                        String key = pi + "/" + ti + "/" + ni;
                        Integer existing = vertMap.get(key);
                        if (existing != null) {
                            idxList.add(existing);
                        } else {
                            Vec3 pos = positions.get(pi);
                            Vec3 nrm = ni < normals.size() && normals.get(ni) != null ? normals.get(ni) : new Vec3(0, 1, 0);
                            float u = ti < texCoords.size() && texCoords.get(ti) != null ? texCoords.get(ti)[0] : 0;
                            float v = ti < texCoords.size() && texCoords.get(ti) != null ? texCoords.get(ti)[1] : 0;
                            Vertex vert = new Vertex(pos, nrm, faceColor, u, v);
                            vertList.add(vert);
                            idxList.add(vertList.size() - 1);
                            vertMap.put(key, vertList.size() - 1);
                        }
                    }
                }
            }

            Vertex[] verts = vertList.toArray(new Vertex[0]);
            int[] indices = idxList.stream().mapToInt(Integer::intValue).toArray();
            return new Mesh(verts, indices);
        }
    }
    
    public static class ShadowMap {
        public float[] depthBuffer;
        public int size;

        public ShadowMap(int size) {
            this.size = size;
            this.depthBuffer = new float[size * size];
            clear();
        }

        public void clear() {
            Arrays.fill(depthBuffer, Float.MAX_VALUE);
        }
    }

    public void enableShadows(int size) {
        shadowMap = new ShadowMap(size);
    }

    public void clearShadowMap() {
        if (shadowMap != null) shadowMap.clear();
    }

    public void setShadowBias(float bias) {
        this.shadowBias = bias;
    }

    public void setShadowIntensity(float intensity) {
        this.shadowIntensity = Math.max(0, Math.min(1, intensity));
    }

    public BufferedImage getShadowMapImage() {
        if (shadowMap == null) return null;
        BufferedImage img = new BufferedImage(shadowMap.size, shadowMap.size, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < shadowMap.depthBuffer.length; i++) {
            int val = (int)(Math.min(1, shadowMap.depthBuffer[i]) * 255);
            int rgb = (val << 16) | (val << 8) | val;
            img.setRGB(i % shadowMap.size, i / shadowMap.size, rgb);
        }
        return img;
    }

    public void setLightMatrix(Vec3 lightPos, Vec3 lightTarget) {
        lightViewMatrix.lookAt(lightPos, lightTarget, new Vec3(0, 1, 0));
        lightProjMatrix.perspective(90, 1, 0.1f, 100);
        lightVP.mul(lightProjMatrix, lightViewMatrix);
    }

    public void renderShadowDepth(Mesh mesh, Matrix4x4 worldMatrix) {
        if (shadowMap == null) return;

        lightMVP.mul(lightVP, worldMatrix);

        for (int i = 0; i < mesh.numTriangles; i++) {
            int i0 = mesh.indices[i * 3];
            int i1 = mesh.indices[i * 3 + 1];
            int i2 = mesh.indices[i * 3 + 2];

            shadowV0.position = mulVec4(mesh.vertices[i0].position, lightMVP);
            shadowV1.position = mulVec4(mesh.vertices[i1].position, lightMVP);
            shadowV2.position = mulVec4(mesh.vertices[i2].position, lightMVP);

            Vertex[] clipped = clipTriangle(shadowV0, shadowV1, shadowV2);
            int numVerts = clipped.length;
            if (numVerts < 3) continue;

            for (int j = 0; j < numVerts; j++) {
                Vertex v = clipped[j];
                float ndcX = v.position.x / v.position.w;
                float ndcY = v.position.y / v.position.w;
                float ndcZ = v.position.z / v.position.w;
                v.depth = ndcZ * 0.5f + 0.5f;
                v.screenX = (int)((ndcX * 0.5f + 0.5f) * shadowMap.size);
                v.screenY = (int)((-ndcY * 0.5f + 0.5f) * shadowMap.size);
            }

            rasterizeShadowDepth(clipped[0], clipped[1], clipped[2]);
            for (int j = 2; j < numVerts - 1; j++) {
                rasterizeShadowDepth(clipped[0], clipped[j], clipped[j+1]);
            }
        }
    }

    private void rasterizeShadowDepth(Vertex v0, Vertex v1, Vertex v2) {
        Vertex[] verts = {v0, v1, v2};
        sortByY(verts);

        if (verts[0].screenY == verts[2].screenY) return;

        int yStart = Math.max(0, verts[0].screenY);
        int yEnd = Math.min(shadowMap.size - 1, verts[2].screenY);

        for (int y = yStart; y <= yEnd; y++) {
            float t1 = (float)(y - verts[0].screenY) / (verts[2].screenY - verts[0].screenY);
            float t2;
            int rightA, rightB;

            int x1 = lerp(verts[0].screenX, verts[2].screenX, t1);
            int x2;
            if (y < verts[1].screenY) {
                float tTop = verts[1].screenY - verts[0].screenY;
                t2 = tTop != 0 ? (y - verts[0].screenY) / tTop : 0;
                x2 = lerp(verts[0].screenX, verts[1].screenX, t2);
                rightA = 0; rightB = 1;
            } else {
                float denom = verts[2].screenY - verts[1].screenY;
                t2 = denom != 0 ? (y - verts[1].screenY) / denom : 0;
                x2 = lerp(verts[1].screenX, verts[2].screenX, t2);
                rightA = 1; rightB = 2;
            }

            float d1 = lerp(verts[0].depth, verts[2].depth, t1);
            float d2 = lerp(verts[rightA].depth, verts[rightB].depth, t2);

            if (x1 > x2) { int t = x1; x1 = x2; x2 = t; float df = d1; d1 = d2; d2 = df; }

            x1 = Math.max(0, x1);
            x2 = Math.min(shadowMap.size - 1, x2);
            if (x1 > x2) continue;

            for (int x = x1; x <= x2; x++) {
                float t = (float)(x - x1) / (x2 - x1 + 1);
                float depth = lerp(d1, d2, t);
                int idx = y * shadowMap.size + x;
                if (depth < shadowMap.depthBuffer[idx]) {
                    shadowMap.depthBuffer[idx] = depth;
                }
            }
        }
    }

    public static class Texture {
        public int[] pixels;
        public int width, height;
        public Texture[] mipmaps;
        public int numMipLevels = 1;
        
        public Texture(int width, int height) {
            this.width = width;
            this.height = height;
            this.pixels = new int[width * height];
        }
        
        public Texture(int width, int height, int color) {
            this(width, height);
            Arrays.fill(pixels, color);
        }

        public void generateMipmaps() {
            int levels = 1;
            int w = width, h = height;
            while (w > 1 || h > 1) { levels++; w = Math.max(1, w / 2); h = Math.max(1, h / 2); }
            mipmaps = new Texture[levels];
            mipmaps[0] = this;
            numMipLevels = levels;
            w = width; h = height;
            for (int i = 1; i < levels; i++) {
                int pw = w, ph = h;
                w = Math.max(1, w / 2); h = Math.max(1, h / 2);
                mipmaps[i] = new Texture(w, h);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int sx = x * 2, sy = y * 2;
                        int c00 = mipmaps[i-1].pixels[sy * pw + sx];
                        int c01 = sx + 1 < pw ? mipmaps[i-1].pixels[sy * pw + sx + 1] : c00;
                        int c10 = sy + 1 < ph ? mipmaps[i-1].pixels[(sy + 1) * pw + sx] : c00;
                        int c11 = (sx + 1 < pw && sy + 1 < ph) ? mipmaps[i-1].pixels[(sy + 1) * pw + sx + 1] : c00;
                        int r = ((c00>>16&0xFF)+(c01>>16&0xFF)+(c10>>16&0xFF)+(c11>>16&0xFF))/4;
                        int g = ((c00>> 8&0xFF)+(c01>> 8&0xFF)+(c10>> 8&0xFF)+(c11>> 8&0xFF))/4;
                        int b = ((c00     &0xFF)+(c01     &0xFF)+(c10     &0xFF)+(c11     &0xFF))/4;
                        mipmaps[i].pixels[y * w + x] = (r << 16) | (g << 8) | b;
                    }
                }
            }
        }

        public int sample(float u, float v, float lod) {
            if (mipmaps == null) {
                float fx = u * width;
                float fy = v * height;
                int tx = Math.floorMod((int)fx, width);
                int ty = Math.floorMod((int)fy, height);
                float fracX = fx - (int)fx;
                float fracY = fy - (int)fy;
                int tx1 = Math.floorMod(tx + 1, width);
                int ty1 = Math.floorMod(ty + 1, height);
                int c00 = pixels[ty * width + tx];
                int c01 = pixels[ty * width + tx1];
                int c10 = pixels[ty1 * width + tx];
                int c11 = pixels[ty1 * width + tx1];
                return bilerp(c00, c01, c10, c11, fracX, fracY);
            }
            float flevel = (float)Math.min(numMipLevels - 1, Math.max(0, lod));
            int level0 = Math.min(numMipLevels - 1, Math.max(0, (int)flevel));
            int level1 = Math.min(numMipLevels - 1, level0 + 1);
            float frac = flevel - level0;
            Texture mip0 = mipmaps[level0];
            Texture mip1 = mipmaps[level1];
            int c0 = sampleBilinear(mip0, u, v);
            int c1 = sampleBilinear(mip1, u, v);
            return lerpRGB(c0, c1, frac);
        }
        
        private int sampleBilinear(Texture mip, float u, float v) {
            float fx = u * mip.width;
            float fy = v * mip.height;
            int tx = Math.floorMod((int)fx, mip.width);
            int ty = Math.floorMod((int)fy, mip.height);
            int tx1 = Math.floorMod(tx + 1, mip.width);
            int ty1 = Math.floorMod(ty + 1, mip.height);
            float fracX = fx - (int)fx;
            float fracY = fy - (int)fy;
            int c00 = mip.pixels[ty * mip.width + tx];
            int c01 = mip.pixels[ty * mip.width + tx1];
            int c10 = mip.pixels[ty1 * mip.width + tx];
            int c11 = mip.pixels[ty1 * mip.width + tx1];
            return bilerp(c00, c01, c10, c11, fracX, fracY);
        }
        
        private static int bilerp(int c00, int c01, int c10, int c11, float fx, float fy) {
            int r00 = (c00 >> 16) & 0xFF, g00 = (c00 >> 8) & 0xFF, b00 = c00 & 0xFF;
            int r01 = (c01 >> 16) & 0xFF, g01 = (c01 >> 8) & 0xFF, b01 = c01 & 0xFF;
            int r10 = (c10 >> 16) & 0xFF, g10 = (c10 >> 8) & 0xFF, b10 = c10 & 0xFF;
            int r11 = (c11 >> 16) & 0xFF, g11 = (c11 >> 8) & 0xFF, b11 = c11 & 0xFF;
            float ix = 1 - fx, iy = 1 - fy;
            int r = (int)((r00 * ix + r01 * fx) * iy + (r10 * ix + r11 * fx) * fy);
            int g = (int)((g00 * ix + g01 * fx) * iy + (g10 * ix + g11 * fx) * fy);
            int b = (int)((b00 * ix + b01 * fx) * iy + (b10 * ix + b11 * fx) * fy);
            return (r << 16) | (g << 8) | b;
        }

        public static Texture createCheckerboard(int width, int height, int size, Color c1, Color c2) {
            Texture tex = new Texture(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    boolean even = ((x / size) + (y / size)) % 2 == 0;
                    tex.pixels[y * width + x] = even ? c1.getRGB() : c2.getRGB();
                }
            }
            return tex;
        }

        public static Texture load(String filename) throws IOException {
            BufferedImage img = javax.imageio.ImageIO.read(new java.io.File(filename));
            int w = img.getWidth(), h = img.getHeight();
            Texture tex = new Texture(w, h);
            img.getRGB(0, 0, w, h, tex.pixels, 0, w);
            tex.generateMipmaps();
            return tex;
        }
    }
    
    public void applySepia() {
        for (int i = 0; i < framebuffer.length; i++) {
            int rgb = framebuffer[i];
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            int tr = (int)(0.393f * r + 0.769f * g + 0.189f * b);
            int tg = (int)(0.349f * r + 0.686f * g + 0.168f * b);
            int tb = (int)(0.272f * r + 0.534f * g + 0.131f * b);
            framebuffer[i] = (Math.min(255, tr) << 16) | (Math.min(255, tg) << 8) | Math.min(255, tb);
        }
    }
    
    public void applyVignette(float intensity) {
        int cx = width / 2, cy = height / 2;
        float maxDist = (float)Math.sqrt(cx * cx + cy * cy);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float dx = x - cx, dy = y - cy;
                float dist = (float)Math.sqrt(dx * dx + dy * dy);
                float factor = 1.0f - (dist / maxDist) * intensity;
                factor = Math.max(0, Math.min(1, factor));
                int idx = y * width + x;
                int r = (int)(((framebuffer[idx] >> 16) & 0xFF) * factor);
                int g = (int)(((framebuffer[idx] >> 8) & 0xFF) * factor);
                int b = (int)((framebuffer[idx] & 0xFF) * factor);
                framebuffer[idx] = (r << 16) | (g << 8) | b;
            }
        }
    }
    
    public void applyPixelate(int pixelSize) {
        int[] src = framebuffer.clone();
        for (int y = 0; y < height; y += pixelSize) {
            for (int x = 0; x < width; x += pixelSize) {
                int r = 0, g = 0, b = 0, count = 0;
                for (int dy = 0; dy < pixelSize && y + dy < height; dy++) {
                    for (int dx = 0; dx < pixelSize && x + dx < width; dx++) {
                        int idx = (y + dy) * width + (x + dx);
                        r += (src[idx] >> 16) & 0xFF;
                        g += (src[idx] >> 8) & 0xFF;
                        b += src[idx] & 0xFF;
                        count++;
                    }
                }
                if (count > 0) {
                    int avg = ((r / count) << 16) | ((g / count) << 8) | (b / count);
                    for (int dy = 0; dy < pixelSize && y + dy < height; dy++) {
                        for (int dx = 0; dx < pixelSize && x + dx < width; dx++) {
                            framebuffer[(y + dy) * width + (x + dx)] = avg;
                        }
                    }
                }
            }
        }
    }
    
    public void saveScreenshot(String filename) throws IOException {
        BufferedImage img = getImage();
        javax.imageio.ImageIO.write(img, "png", new java.io.File(filename));
    }
    
    public void saveScreenshot() throws IOException {
        saveScreenshot("screenshot_" + System.currentTimeMillis() + ".png");
    }
    
    public void toggleWireframe() {
        renderMode = renderMode == RenderMode.WIREFRAME ? RenderMode.SOLID : RenderMode.WIREFRAME;
    }
    
    public void setDayNightCycle(float timeOfDay) {
        float sunAngle = timeOfDay * 2 * (float)Math.PI;
        float sunHeight = (float)Math.sin(sunAngle);
        float sunHorizontal = (float)Math.cos(sunAngle);
        lightDir = new Vec3(sunHorizontal * 0.5f, sunHeight, 0.3f).normalize();
        ambientIntensity = 0.1f + 0.4f * Math.max(0, sunHeight);
        diffuseIntensity = 0.3f + 0.7f * Math.max(0, sunHeight);
    }    
    // ==================== OPENGL SHADER SUPPORT ====================
    
    public static class Shader {
        private int programID;
        private Map<String, Integer> uniforms = new HashMap<>();
        
        public Shader(String vertexSource, String fragmentSource) {
            programID = createProgram(vertexSource, fragmentSource);
        }
        
        private int createProgram(String vertexSource, String fragmentSource) {
            // This is a software shader emulation
            // For real OpenGL, you would use GLSL compilation
            return 0;
        }
        
        public void use() { /* OpenGL: glUseProgram(programID); */ }
        public void setUniform(String name, float value) { }
        public void setUniform(String name, float[] matrix) { }
        public void setUniform(String name, int value) { }
        public int getProgramID() { return programID; }
    }
    
    public static class ShaderManager {
        public static Map<String, Shader> shaders = new HashMap<>();
        
        public static Shader loadShader(String name, String vertexPath, String fragmentPath) {
            try {
                String vertexSource = readFile(vertexPath);
                String fragmentSource = readFile(fragmentPath);
                Shader shader = new Shader(vertexSource, fragmentSource);
                shaders.put(name, shader);
                return shader;
            } catch (Exception e) {
                return null;
            }
        }
        
        private static String readFile(String path) throws IOException {
            return new String(Files.readAllBytes(Paths.get(path)));
        }
    }
    
    public void setShader(String name) {
        Shader shader = ShaderManager.shaders.get(name);
        if (shader != null) shader.use();
    }    
    // ==================== PBR MATERIALS ====================
    
    public static class PBRMaterial {
        public Color albedo = new Color(180, 180, 180);
        public float metallic = 0.0f;
        public float roughness = 0.5f;
        public float ao = 1.0f;
        public float emissive = 0.0f;
        public Texture albedoMap;
        public Texture metallicMap;
        public Texture roughnessMap;
        public Texture aoMap;
        public Texture normalMap;
        public Texture emissiveMap;
        
        public PBRMaterial() {}
        
        public PBRMaterial(Color albedo, float metallic, float roughness) {
            this.albedo = albedo;
            this.metallic = metallic;
            this.roughness = roughness;
        }
    }
    
    public static class PBRRenderer {
        public static Color calculatePBR(Color albedo, float metallic, float roughness, 
                                        Vec3 normal, Vec3 viewDir, Vec3 lightDir, 
                                        Vec3 lightColor, float intensity) {
            // Fresnel
            Vec3 halfDir = lightDir.add(viewDir).normalize();
            float NdotL = Math.max(0, normal.dot(lightDir));
            float NdotV = Math.max(0, normal.dot(viewDir));
            float NdotH = Math.max(0, normal.dot(halfDir));
            
            // Diffuse (Lambertian)
            float diffuse = NdotL;
            
            // Specular (GGX)
            float alpha = roughness * roughness;
            float alpha2 = alpha * alpha;
            float denom = NdotH * NdotH * (alpha2 - 1) + 1;
            float D = alpha2 / (Math.max(0.0001f, (float)Math.PI) * denom * denom);
            
            // Geometry (Schlick-GGX)
            float k = alpha / 2;
            float G1 = NdotL / (NdotL * (1 - k) + k);
            float G2 = NdotV / (NdotV * (1 - k) + k);
            float G = G1 * G2;
            
            // Fresnel (Schlick)
            float F0 = 0.04f + (1 - 0.04f) * metallic;
            float F = F0 + (1 - F0) * (float)Math.pow(1 - NdotV, 5);
            
            // Cook-Torrance BRDF
            float specular = D * F * G / (4 * NdotV * NdotL + 0.0001f);
            
            // Combine
            float r = (albedo.getRed() / 255f) * (diffuse + specular * metallic) * intensity;
            float g = (albedo.getGreen() / 255f) * (diffuse + specular * metallic) * intensity;
            float b = (albedo.getBlue() / 255f) * (diffuse + specular * metallic) * intensity;
            
            return new Color(Math.min(255, (int)(r * 255)), 
                           Math.min(255, (int)(g * 255)), 
                           Math.min(255, (int)(b * 255)));
        }
    }    
    // ==================== DEFERRED RENDERING ====================
    
    public static class GBuffer {
        public int[] albedo = new int[0];
        public float[] normal = new float[0];
        public float[] position = new float[0];
        public float[] metallic = new float[0];
        public float[] roughness = new float[0];
        public float[] depth = new float[0];
        public int width, height;
        
        public GBuffer(int width, int height) {
            this.width = width;
            this.height = height;
            int count = width * height;
            albedo = new int[count];
            normal = new float[count * 3];
            position = new float[count * 3];
            metallic = new float[count];
            roughness = new float[count];
            depth = new float[count];
        }
        
        public void clear() {
            Arrays.fill(albedo, 0);
            Arrays.fill(normal, 0);
            Arrays.fill(position, 0);
            Arrays.fill(metallic, 0);
            Arrays.fill(roughness, 0);
            Arrays.fill(depth, Float.MAX_VALUE);
        }
    }
    
    private GBuffer gBuffer;
    
    public void enableDeferredRendering() {
        gBuffer = new GBuffer(width, height);
    }
    
    public void renderDeferred() {
        if (gBuffer == null) return;
        
        // Light pass (screen quad)
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                if (gBuffer.depth[idx] < Float.MAX_VALUE) {
                    // Read from G-buffer and compute lighting
                    int albedo = gBuffer.albedo[idx];
                    float nx = gBuffer.normal[idx * 3];
                    float ny = gBuffer.normal[idx * 3 + 1];
                    float nz = gBuffer.normal[idx * 3 + 2];
                    float metallic = gBuffer.metallic[idx];
                    float roughness = gBuffer.roughness[idx];
                    
                    // Lighting calculation
                    Vec3 normal = new Vec3(nx, ny, nz).normalize();
                    Color albedoColor = new Color((albedo >> 16) & 0xFF, 
                                                 (albedo >> 8) & 0xFF, 
                                                 albedo & 0xFF);
                    // Apply lighting...
                    framebuffer[idx] = albedo; // Simplified
                }
            }
        }
    }
    
    public void setMetallicRoughness(float metallic, float roughness) {
        // Store in g-buffer during render
    }    
    // ==================== GPU INSTANCING ====================
    
    public static class InstanceData {
        public Matrix4x4[] transforms;
        public int[] colors;
        public int count;
        
        public InstanceData(int count) {
            this.count = count;
            transforms = new Matrix4x4[count];
            colors = new int[count];
            for (int i = 0; i < count; i++) {
                transforms[i] = new Matrix4x4();
            }
        }
    }
    
    public void renderInstancedGL(Mesh mesh, InstanceData instances, Texture texture) {
        for (int i = 0; i < instances.count; i++) {
            renderMesh(mesh, instances.transforms[i], texture);
        }
    }
    
    public void renderInstancedBatch(Mesh mesh, InstanceData instances, Texture texture) {
        // Batch process for better performance
        for (int batch = 0; batch < instances.count; batch += 100) {
            int end = Math.min(batch + 100, instances.count);
            for (int i = batch; i < end; i++) {
                renderMesh(mesh, instances.transforms[i], texture);
            }
        }
    }
    
    public InstanceData createInstanceGrid(int count, float spacing) {
        int side = (int)Math.sqrt(count);
        InstanceData data = new InstanceData(side * side);
        int idx = 0;
        for (int z = -side/2; z < side/2; z++) {
            for (int x = -side/2; x < side/2; x++) {
                if (idx < data.count) {
                    data.transforms[idx].translate(x * spacing, 0, z * spacing);
                    data.colors[idx] = (int)(Math.random() * 0xFFFFFF);
                    idx++;
                }
            }
        }
        return data;
    }    
    // ==================== HDR + TONE MAPPING ====================
    
    private float[] hdrBuffer;
    private boolean hdrEnabled = false;
    private float exposure = 1.0f;
    private int tonemapMode = 0; // 0=Reinhard, 1=ACES, 2=Uncharted
    
    public void enableHDR() {
        hdrBuffer = new float[width * height * 3];
        hdrEnabled = true;
    }
    
    public void disableHDR() {
        hdrEnabled = false;
        hdrBuffer = null;
    }
    
    public void setExposure(float exposure) {
        this.exposure = exposure;
    }
    
    public void setTonemapMode(int mode) {
        this.tonemapMode = mode;
    }
    
    public void tonemap() {
        if (!hdrEnabled || hdrBuffer == null) return;
        
        for (int i = 0; i < width * height; i++) {
            float r = hdrBuffer[i * 3];
            float g = hdrBuffer[i * 3 + 1];
            float b = hdrBuffer[i * 3 + 2];
            
            // Apply exposure
            r *= exposure;
            g *= exposure;
            b *= exposure;
            
            // Tone mapping
            float[] color = tonemap(r, g, b, tonemapMode);
            
            // Gamma correction
            color[0] = (float)Math.pow(color[0], 1f / 2.2f);
            color[1] = (float)Math.pow(color[1], 1f / 2.2f);
            color[2] = (float)Math.pow(color[2], 1f / 2.2f);
            
            framebuffer[i] = ((int)(color[0] * 255) << 16) |
                           ((int)(color[1] * 255) << 8) |
                            (int)(color[2] * 255);
        }
    }
    
    private float[] tonemap(float r, float g, float b, int mode) {
        switch(mode) {
            case 0: // Reinhard
                float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                float scale = 1.0f / (1.0f + lum);
                return new float[]{r * scale, g * scale, b * scale};
            case 1: // ACES
                float a = 2.51f, b1 = 0.03f, c = 2.43f, d = 0.59f, e = 0.14f;
                float[] result = new float[3];
                for (int i = 0; i < 3; i++) {
                    float v = new float[]{r, g, b}[i];
                    result[i] = Math.max(0, Math.min(1, (v * (a * v + b1)) / (v * (c * v + d) + e)));
                }
                return result;
            case 2: // Uncharted 2
                float A = 0.15f, B = 0.50f, C = 0.10f, D = 0.20f, E = 0.02f, F = 0.30f;
                float[] u = new float[3];
                for (int i = 0; i < 3; i++) {
                    float v = new float[]{r, g, b}[i];
                    u[i] = ((v * (A * v + C * B) + D * E) / (v * (A * v + B) + D * F)) - E / F;
                }
                float white = ((1.0f * (A * 1.0f + C * B) + D * E) / (1.0f * (A * 1.0f + B) + D * F)) - E / F;
                return new float[]{u[0]/white, u[1]/white, u[2]/white};
            default:
                return new float[]{r, g, b};
        }
    }    
    // ==================== COMPUTE SHADER EMULATION ====================
    
    public interface ComputeKernel {
        void execute(int id);
    }
    
    public static class ComputeShader {
        private String name;
        private ComputeKernel kernel;
        private int workGroupSize;
        private int[] workGroupOutput;
        
        public ComputeShader(String name, int workGroupSize, ComputeKernel kernel) {
            this.name = name;
            this.workGroupSize = workGroupSize;
            this.kernel = kernel;
            this.workGroupOutput = new int[workGroupSize];
        }
        
        public void dispatch(int groups) {
            int totalWork = groups * workGroupSize;
            // Multi-threaded execution
            int numThreads = Math.min(Runtime.getRuntime().availableProcessors(), totalWork);
            Thread[] threads = new Thread[numThreads];
            int workPerThread = totalWork / numThreads;
            
            for (int t = 0; t < numThreads; t++) {
                int start = t * workPerThread;
                int end = (t == numThreads - 1) ? totalWork : (t + 1) * workPerThread;
                threads[t] = new Thread(() -> {
                    for (int i = start; i < end; i++) {
                        kernel.execute(i);
                    }
                });
                threads[t].start();
            }
            
            for (Thread thread : threads) {
                try { thread.join(); } catch (InterruptedException e) {}
            }
        }
    }
    
    public static class ComputeShaderManager {
        public static Map<String, ComputeShader> shaders = new HashMap<>();
        
        public static ComputeShader createShader(String name, int workGroupSize, ComputeKernel kernel) {
            ComputeShader shader = new ComputeShader(name, workGroupSize, kernel);
            shaders.put(name, shader);
            return shader;
        }
        
        public static void dispatch(String name, int groups) {
            ComputeShader shader = shaders.get(name);
            if (shader != null) shader.dispatch(groups);
        }
    }
    
    public void applyComputeEffect(String shaderName, int groups) {
        ComputeShaderManager.dispatch(shaderName, groups);
    }
    
    // Example compute kernels
    public static class ComputeKernels {
        public static ComputeKernel blurKernel = (id) -> {
            // Simplified blur
            // Would normally process image data
        };
        
        public static ComputeKernel edgeDetectKernel = (id) -> {
            // Sobel edge detection
        };
        
        public static ComputeKernel particleUpdateKernel = (id) -> {
            // Update particle positions
        };
    }
    
    // ============================================================
    // TILE-BASED RENDERING
    // ============================================================
    public static class TileRegion {
        public int x, y, w, h;
        public TileRegion(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }
    }
    
    static class TriangleBatch {
        Mesh mesh; Matrix4x4 world; Texture tex, nm; int[] indices; int offset, count; boolean shadow;
        TriangleBatch(Mesh m, Matrix4x4 w, Texture t, Texture n, int[] idx, int off, int cnt, boolean s) {
            mesh = m; world = w; tex = t; nm = n; indices = idx; offset = off; count = cnt; shadow = s;
        }
    }
    
    public void renderTileBased() {
        if (!useTileBased || threadPool == null) return;
        long t0 = System.nanoTime();
        int numTiles = tileRegions.length;
        for (int i = 0; i < numTiles; i++) {
            if (!tileBatches[i].isEmpty()) {
                final int ti = i;
                threadPool.execute(() -> processTile(ti));
            }
        }
        // Wait via ForkJoinPool common quiescence
        while (!threadPool.isQuiescent()) Thread.yield();
        // Clear batches
        for (int i = 0; i < numTiles; i++) tileBatches[i].clear();
        statsRasterTime = System.nanoTime() - t0;
    }
    
    private void processTile(int tileIdx) {
        TileRegion tr = tileRegions[tileIdx];
        java.util.List<TriangleBatch> batches = tileBatches[tileIdx];
        int tx1 = tr.x, ty1 = tr.y, tx2 = tr.x + tr.w, ty2 = tr.y + tr.h;
        for (TriangleBatch batch : batches) {
            for (int t = batch.offset; t < batch.offset + batch.count; t++) {
                int i0 = batch.indices[t * 3], i1 = batch.indices[t * 3 + 1], i2 = batch.indices[t * 3 + 2];
                Vertex v0 = batch.mesh.vertices[i0], v1 = batch.mesh.vertices[i1], v2 = batch.mesh.vertices[i2];
                // Quick AABB test against tile
                int minX = Math.max(tx1, Math.max(0, Math.min(v0.screenX, Math.min(v1.screenX, v2.screenX))));
                int maxX = Math.min(tx2 - 1, Math.min(width - 1, Math.max(v0.screenX, Math.max(v1.screenX, v2.screenX))));
                int minY = Math.max(ty1, Math.max(0, Math.min(v0.screenY, Math.min(v1.screenY, v2.screenY))));
                int maxY = Math.min(ty2 - 1, Math.min(height - 1, Math.max(v0.screenY, Math.max(v1.screenY, v2.screenY))));
                if (minX > maxX || minY > maxY) continue;
                if (batch.tex != null) rasterizeTexturedTriangleClipped(v0, v1, v2, batch.tex, batch.nm, minX, maxX, minY, maxY);
                else rasterizeTriangleClipped(v0, v1, v2, minX, maxX, minY, maxY);
            }
        }
    }
    
    private void rasterizeTriangleClipped(Vertex v0, Vertex v1, Vertex v2, int minX, int maxX, int minY, int maxY) {
        Vertex[] verts = {v0, v1, v2}; sortByY(verts);
        if (verts[0].screenY == verts[2].screenY) return;
        int yStart = Math.max(minY, verts[0].screenY), yEnd = Math.min(maxY, verts[2].screenY);
        if (yStart > yEnd) return;
        for (int y = yStart; y <= yEnd; y++) {
            float t1 = (y - verts[0].screenY) / (float)(verts[2].screenY - verts[0].screenY);
            int x1 = lerp(verts[0].screenX, verts[2].screenX, t1), x2;
            float z1 = lerp(verts[0].depth, verts[2].depth, t1), z2;
            float iw1 = lerp(verts[0].invW, verts[2].invW, t1), iw2;
            Color c1 = lerpColor(verts[0].color, verts[2].color, t1), c2;
            if (y < verts[1].screenY) {
                float t2 = (verts[1].screenY - verts[0].screenY) != 0 ? (y - verts[0].screenY) / (float)(verts[1].screenY - verts[0].screenY) : 0;
                x2 = lerp(verts[0].screenX, verts[1].screenX, t2); z2 = lerp(verts[0].depth, verts[1].depth, t2);
                iw2 = lerp(verts[0].invW, verts[1].invW, t2); c2 = lerpColor(verts[0].color, verts[1].color, t2);
            } else {
                float t2 = (verts[2].screenY - verts[1].screenY) != 0 ? (y - verts[1].screenY) / (float)(verts[2].screenY - verts[1].screenY) : 0;
                x2 = lerp(verts[1].screenX, verts[2].screenX, t2); z2 = lerp(verts[1].depth, verts[2].depth, t2);
                iw2 = lerp(verts[1].invW, verts[2].invW, t2); c2 = lerpColor(verts[1].color, verts[2].color, t2);
            }
            if (x1 > x2) { int t = x1; x1 = x2; x2 = t; float f = z1; z1 = z2; z2 = f; f = iw1; iw1 = iw2; iw2 = f; Color tc = c1; c1 = c2; c2 = tc; }
            x1 = Math.max(minX, x1); x2 = Math.min(maxX, x2); if (x1 > x2) continue;
            for (int x = x1; x <= x2; x++) {
                float t = (x - x1) / (float)(x2 - x1 + 1);
                float depth = lerp(z1, z2, t); int idx = y * width + x;
                if (depth < zBuffer[idx]) { zBuffer[idx] = depth; writeFragment(idx, lerpColor(c1, c2, t), depth); }
            }
        }
    }
    
    private void rasterizeTexturedTriangleClipped(Vertex v0, Vertex v1, Vertex v2, Texture texture, Texture normalMap, int minX, int maxX, int minY, int maxY) {
        Vertex[] verts = {v0, v1, v2}; sortByY(verts);
        if (verts[0].screenY == verts[2].screenY) return;
        int yStart = Math.max(minY, verts[0].screenY), yEnd = Math.min(maxY, verts[2].screenY);
        if (yStart > yEnd) return;
        for (int y = yStart; y <= yEnd; y++) {
            float t1 = (y - verts[0].screenY) / (float)(verts[2].screenY - verts[0].screenY);
            int x1 = lerp(verts[0].screenX, verts[2].screenX, t1), x2;
            float z1 = lerp(verts[0].depth, verts[2].depth, t1), z2, u1 = lerp(verts[0].u, verts[2].u, t1), u2;
            float vv1 = lerp(verts[0].v, verts[2].v, t1), vv2, iw1 = lerp(verts[0].invW, verts[2].invW, t1), iw2;
            Color c1 = lerpColor(verts[0].color, verts[2].color, t1), c2;
            if (y < verts[1].screenY) {
                float t2 = (verts[1].screenY - verts[0].screenY) != 0 ? (y - verts[0].screenY) / (float)(verts[1].screenY - verts[0].screenY) : 0;
                x2 = lerp(verts[0].screenX, verts[1].screenX, t2); z2 = lerp(verts[0].depth, verts[1].depth, t2);
                u2 = lerp(verts[0].u, verts[1].u, t2); vv2 = lerp(verts[0].v, verts[1].v, t2);
                iw2 = lerp(verts[0].invW, verts[1].invW, t2); c2 = lerpColor(verts[0].color, verts[1].color, t2);
            } else {
                float t2 = (verts[2].screenY - verts[1].screenY) != 0 ? (y - verts[1].screenY) / (float)(verts[2].screenY - verts[1].screenY) : 0;
                x2 = lerp(verts[1].screenX, verts[2].screenX, t2); z2 = lerp(verts[1].depth, verts[2].depth, t2);
                u2 = lerp(verts[1].u, verts[2].u, t2); vv2 = lerp(verts[1].v, verts[2].v, t2);
                iw2 = lerp(verts[1].invW, verts[2].invW, t2); c2 = lerpColor(verts[1].color, verts[2].color, t2);
            }
            if (x1 > x2) { int t = x1; x1 = x2; x2 = t; float f; f = z1; z1 = z2; z2 = f; f = u1; u1 = u2; u2 = f; f = vv1; vv1 = vv2; vv2 = f; f = iw1; iw1 = iw2; iw2 = f; Color tc = c1; c1 = c2; c2 = tc; }
            x1 = Math.max(minX, x1); x2 = Math.min(maxX, x2); if (x1 > x2) continue;
            for (int x = x1; x <= x2; x++) {
                float t = (x - x1) / (float)(x2 - x1 + 1);
                float depth = lerp(z1, z2, t); int idx = y * width + x;
                if (depth < zBuffer[idx]) {
                    zBuffer[idx] = depth;
                    float iw = lerp(iw1, iw2, t);
                    float u = ((lerp(u1, u2, t) / iw % 1f) + 1f) % 1f;
                    float v = ((lerp(vv1, vv2, t) / iw % 1f) + 1f) % 1f;
                    writeFragment(idx, modulateColor(lerpColor(c1, c2, t), new Color(texture.sample(u, v, 0))), depth);
                }
            }
        }
    }
    // ============================================================
    // SCENE GRAPH
    // ============================================================
    public static class Node {
        public String name;
        public Node parent;
        public java.util.List<Node> children = new ArrayList<>();
        public Matrix4x4 localTransform = new Matrix4x4();
        public Matrix4x4 worldTransform = new Matrix4x4();
        public Mesh mesh;
        public Texture texture, normalMap;
        public boolean visible = true;
        
        public Node(String name) { this.name = name; }
        public Node addChild(Node child) { child.parent = this; children.add(child); return this; }
        public Node translate(float x, float y, float z) { localTransform.translate(x, y, z); return this; }
        public Node rotateX(float a) { localTransform.rotateX(a); return this; }
        public Node rotateY(float a) { localTransform.rotateY(a); return this; }
        public Node rotateZ(float a) { localTransform.rotateZ(a); return this; }
        public Node scale(float x, float y, float z) { localTransform.scale(x, y, z); return this; }
        public Node setMesh(Mesh m) { mesh = m; return this; }
        public Node setTexture(Texture t) { texture = t; return this; }
        
        public void updateWorld(Matrix4x4 parentWorld) {
            worldTransform.mul(parentWorld, localTransform);
            for (Node child : children) child.updateWorld(worldTransform);
        }
    }
    
    public void renderNode(Node node) {
        if (!node.visible) return;
        if (node.mesh != null) renderMesh(node.mesh, node.worldTransform, node.texture, node.normalMap);
        for (Node child : node.children) renderNode(child);
    }
    
    // ============================================================
    // INPUT SYSTEM
    // ============================================================
    public static class Input {
        public boolean[] keys = new boolean[512];
        public boolean[] mouseButtons = new boolean[8];
        public int mouseX, mouseY, mouseDX, mouseDY;
        public boolean mouseInWindow;
        
        public boolean key(int code) { return code >= 0 && code < keys.length && keys[code]; }
        public boolean keyPressed(int code) { return key(code); }
        public boolean mouseButton(int btn) { return btn >= 0 && btn < mouseButtons.length && mouseButtons[btn]; }
        
        public void update() { mouseDX = 0; mouseDY = 0; }
        
        public java.awt.event.KeyAdapter keyAdapter() {
            return new java.awt.event.KeyAdapter() {
                public void keyPressed(java.awt.event.KeyEvent e) { if (e.getKeyCode() < keys.length) keys[e.getKeyCode()] = true; }
                public void keyReleased(java.awt.event.KeyEvent e) { if (e.getKeyCode() < keys.length) keys[e.getKeyCode()] = false; }
            };
        }
        
        public java.awt.event.MouseAdapter mouseAdapter() {
            return new java.awt.event.MouseAdapter() {
                public void mousePressed(java.awt.event.MouseEvent e) { if (e.getButton() < mouseButtons.length) mouseButtons[e.getButton()] = true; }
                public void mouseReleased(java.awt.event.MouseEvent e) { if (e.getButton() < mouseButtons.length) mouseButtons[e.getButton()] = false; }
                public void mouseMoved(java.awt.event.MouseEvent e) { mouseDX = e.getX() - mouseX; mouseDY = e.getY() - mouseY; mouseX = e.getX(); mouseY = e.getY(); }
                public void mouseDragged(java.awt.event.MouseEvent e) { mouseDX = e.getX() - mouseX; mouseDY = e.getY() - mouseY; mouseX = e.getX(); mouseY = e.getY(); }
                public void mouseEntered(java.awt.event.MouseEvent e) { mouseInWindow = true; }
                public void mouseExited(java.awt.event.MouseEvent e) { mouseInWindow = false; }
            };
        }
    }
    
    // ============================================================
    // PHYSICS
    // ============================================================
    public static class AABB {
        public Vec3 min = new Vec3(), max = new Vec3();
        public AABB() {}
        public AABB(Vec3 min, Vec3 max) { this.min = min; this.max = max; }
        public AABB centerExtents(Vec3 center, Vec3 extents) { min = center.sub(extents); max = center.add(extents); return this; }
        public boolean overlaps(AABB other) {
            return min.x <= other.max.x && max.x >= other.min.x &&
                   min.y <= other.max.y && max.y >= other.min.y &&
                   min.z <= other.max.z && max.z >= other.min.z;
        }
        public Vec3 center() { return new Vec3((min.x + max.x) * 0.5f, (min.y + max.y) * 0.5f, (min.z + max.z) * 0.5f); }
    }
    
    public static class RigidBody {
        public Vec3 position = new Vec3(), velocity = new Vec3();
        public AABB bounds = new AABB();
        public float mass = 1f, restitution = 0.5f;
        public boolean useGravity = true, isStatic = false;
        
        public RigidBody(Vec3 pos, Vec3 size) {
            position = pos; bounds.centerExtents(pos, size);
        }
        
        public void update(float dt, Vec3 gravity) {
            if (isStatic) return;
            if (useGravity) velocity = velocity.add(gravity.scale(dt));
            position = position.add(velocity.scale(dt));
            bounds.centerExtents(position, bounds.center().sub(bounds.min));
        }
        
        public boolean collide(RigidBody other) {
            if (!bounds.overlaps(other.bounds)) return false;
            Vec3 overlap = new Vec3(
                Math.min(bounds.max.x - other.bounds.min.x, other.bounds.max.x - bounds.min.x),
                Math.min(bounds.max.y - other.bounds.min.y, other.bounds.max.y - bounds.min.y),
                Math.min(bounds.max.z - other.bounds.min.z, other.bounds.max.z - bounds.min.z));
            float minOverlap = Math.min(overlap.x, Math.min(overlap.y, overlap.z));
            Vec3 normal;
            if (overlap.x == minOverlap) normal = new Vec3(velocity.x > 0 ? -1 : 1, 0, 0);
            else if (overlap.y == minOverlap) normal = new Vec3(0, velocity.y > 0 ? -1 : 1, 0);
            else normal = new Vec3(0, 0, velocity.z > 0 ? -1 : 1);
            float impulse = -(1 + restitution) * velocity.dot(normal) / (1/mass + 1/other.mass);
            velocity = velocity.add(normal.scale(impulse / mass));
            position = position.add(normal.scale(minOverlap * 0.5f));
            other.position = other.position.add(normal.scale(-minOverlap * 0.5f));
            return true;
        }
    }
    
    // ============================================================
    // SHADER INTERFACE
    // ============================================================
    @FunctionalInterface
    public interface VertexShader {
        Vec4 process(Vec4 position, Vec3 normal, Vec2 uv, Color color);
    }
    
    @FunctionalInterface
    public interface FragmentShader {
        int process(Vec3 barycentric, Vec3 worldPos, Vec3 normal, Vec2 uv, Color color);
    }
    
    public static class Vec2 { public float x, y; public Vec2() {} public Vec2(float x, float y) { this.x = x; this.y = y; } }
    
    public static class ShaderProgram {
        public VertexShader vertex;
        public FragmentShader fragment;
        public Texture texture, normalMap;
        public boolean hasTexture = false;
        
        public ShaderProgram(VertexShader vs, FragmentShader fs) { vertex = vs; fragment = fs; }
        public ShaderProgram texture(Texture t) { texture = t; hasTexture = true; return this; }
        public ShaderProgram normalMap(Texture n) { normalMap = n; return this; }
    }
    
    public void renderWithShader(Mesh mesh, Matrix4x4 world, ShaderProgram shader) {
        // Override vertex processing with custom shader
        mvpMatrix.mul(viewProjMatrix, world);
        for (int i = 0; i < mesh.numTriangles; i++) {
            int i0 = mesh.indices[i * 3], i1 = mesh.indices[i * 3 + 1], i2 = mesh.indices[i * 3 + 2];
            Vertex v0 = mesh.vertices[i0], v1 = mesh.vertices[i1], v2 = mesh.vertices[i2];
            if (shader.vertex != null) {
                Vec4 p0 = shader.vertex.process(v0.position, v0.normal, new Vec2(v0.u, v0.v), v0.color);
                Vec4 p1 = shader.vertex.process(v1.position, v1.normal, new Vec2(v1.u, v1.v), v1.color);
                Vec4 p2 = shader.vertex.process(v2.position, v2.normal, new Vec2(v2.u, v2.v), v2.color);
                v0.position = p0; v1.position = p1; v2.position = p2;
            }
            renderMesh(mesh, world, shader.hasTexture ? shader.texture : null, shader.normalMap);
        }
    }
    
    // ============================================================
    // OBJECT POOL
    // ============================================================
    public static class VertexPool {
        private final java.util.List<Vertex> pool = new ArrayList<>();
        private int taken = 0;
        
        public Vertex get() {
            if (taken < pool.size()) return pool.get(taken++);
            Vertex v = new Vertex(); pool.add(v); taken++; return v;
        }
        
        public void reset() { taken = 0; }
        public int active() { return taken; }
        public int total() { return pool.size(); }
    }
    
    // ============================================================
    // AUDIO
    // ============================================================
    public static class Audio {
        private javax.sound.sampled.SourceDataLine line;
        private boolean available = false;
        
        public Audio() {
            try {
                javax.sound.sampled.AudioFormat fmt = new javax.sound.sampled.AudioFormat(44100, 16, 1, true, false);
                line = javax.sound.sampled.AudioSystem.getSourceDataLine(fmt);
                line.open(fmt, 4096);
                line.start();
                available = true;
            } catch (Exception e) { available = false; }
        }
        
        public void playTone(float freq, float duration, float volume) {
            if (!available) return;
            int samples = (int)(44100 * duration);
            byte[] buf = new byte[samples * 2];
            for (int i = 0; i < samples; i++) {
                float t = (float)i / 44100;
                float env = Math.min(1, (samples - i) / (44100f * 0.05f));
                short s = (short)(volume * 32767 * Math.sin(2 * Math.PI * freq * t) * env);
                buf[i * 2] = (byte)(s & 0xFF);
                buf[i * 2 + 1] = (byte)((s >> 8) & 0xFF);
            }
            line.write(buf, 0, buf.length);
        }
        
        public void playClick() { playTone(1000, 0.05f, 0.3f); }
        public void playHit() { playTone(200, 0.15f, 0.5f); }
        public void close() { if (line != null) { line.drain(); line.close(); } }
    }
}






