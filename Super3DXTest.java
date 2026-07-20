import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class Super3DXTest extends JPanel implements Runnable, KeyListener {
    private Super3DX engine;
    private Super3DX.Mesh cube, ground, bunny;
    private Super3DX.Texture checkerTex, groundTex, normalMapTex, skyTex;
    private Super3DX.ParticleEmitter emitter;
    private Super3DX.Texture particleTex;
    private Super3DX.Skeleton skeleton;
    private Super3DX.AnimationClip animClip;
    private Super3DX.Mesh animMesh;
    private float angleX = 0, angleY = 0, animTime = 0;
    private boolean running = true, wireframe = false, gamma = false, bloom = false;
    private boolean fog = false, fxaa = false, particles = true, normalMap = false, skybox = true;
    private boolean animating = false;
    private int fps = 0, frameCount = 0;
    private long lastFpsTime = System.nanoTime();
    private Thread renderThread;
    private boolean stopRequested = false;
    private String modeStr = "SOLID";

    public Super3DXTest() {
        setPreferredSize(new Dimension(800, 600));
        setFocusable(true);
        addKeyListener(this);

        engine = new Super3DX(800, 600);
        engine.setBackfaceCulling(true);
        engine.setLighting(0.3f, 0.7f);
        engine.setSpecular(0.6f, 32f);
        engine.setLightDirection(new Super3DX.Vec3(0.5f, -0.7f, 0.3f));

        // Skybox texture
        skyTex = Super3DX.Texture.createCheckerboard(256, 256, 32,
            new Color(30, 40, 70), new Color(60, 80, 120));

        // Diffuse textures
        checkerTex = Super3DX.Texture.createCheckerboard(64, 64, 8,
            new Color(255, 50, 50), new Color(50, 150, 255));
        groundTex = Super3DX.Texture.createCheckerboard(64, 64, 8,
            new Color(100, 100, 100), new Color(60, 60, 60));

        // Procedural normal map (bump pattern)
        normalMapTex = createBumpNormalMap(64, 64);

        // Particle texture
        particleTex = createParticleTexture(16, 16);

        // Cube
        cube = Super3DX.Mesh.createTexturedCube(1.5f, checkerTex);

        // Ground plane
        ground = createGroundPlane(8, groundTex);

        // Particles
        emitter = new Super3DX.ParticleEmitter();
        emitter.position = new Super3DX.Vec3(0, 0.5f, 0);
        emitter.spawnRate = 30f;
        emitter.particleLife = 2f;
        emitter.particleSize = 0.12f;
        emitter.speed = 1.2f;
        emitter.spread = 1.5f;
        emitter.startColor = new Color(255, 200, 50);
        emitter.endColor = new Color(255, 50, 50, 0);

        // Animation rig (simple test)
        setupAnimation();

        // Shadows
        engine.enableShadows(256);
        engine.setLightMatrix(
            new Super3DX.Vec3(2, 4, 3),
            new Super3DX.Vec3(0, 0, 0)
        );

        lastFpsTime = System.nanoTime();
        renderThread = new Thread(this);
        renderThread.start();
    }

    private Super3DX.Texture createBumpNormalMap(int w, int h) {
        Super3DX.Texture tex = new Super3DX.Texture(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float nx = (float)Math.sin(x * 0.3f) * (float)Math.cos(y * 0.2f);
                float ny = (float)Math.sin(y * 0.3f) * (float)Math.cos(x * 0.2f);
                float nz = (float)Math.sqrt(Math.max(0, 1 - nx*nx - ny*ny));
                int r = (int)((nx * 0.5f + 0.5f) * 255);
                int g = (int)((ny * 0.5f + 0.5f) * 255);
                int b = (int)(nz * 255);
                tex.pixels[y * w + x] = (r << 16) | (g << 8) | b;
            }
        }
        tex.generateMipmaps();
        return tex;
    }

    private Super3DX.Texture createParticleTexture(int w, int h) {
        Super3DX.Texture tex = new Super3DX.Texture(w, h);
        int cx = w/2, cy = h/2, r = w/2;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x - cx, dy = y - cy;
                float dist = (float)Math.sqrt(dx*dx + dy*dy);
                float alpha = Math.max(0, 1 - dist / r) * 255;
                tex.pixels[y * w + x] = ((int)alpha << 24) | 0xFFFFFF;
            }
        }
        return tex;
    }

    private void setupAnimation() {
        skeleton = new Super3DX.Skeleton(2);
        skeleton.parentIndices[0] = -1; // root
        skeleton.parentIndices[1] = 0;  // child of root

        animClip = new Super3DX.AnimationClip(2, 2f);
        // Bone 0 keyframes
        Super3DX.AnimationKeyframe k0 = new Super3DX.AnimationKeyframe();
        k0.time = 0; k0.translation = new Super3DX.Vec3(0, 0, 0);
        animClip.keyframes[0].add(k0);
        Super3DX.AnimationKeyframe k1 = new Super3DX.AnimationKeyframe();
        k1.time = 1f; k1.translation = new Super3DX.Vec3(0, 0.3f, 0);
        animClip.keyframes[0].add(k1);
        Super3DX.AnimationKeyframe k2 = new Super3DX.AnimationKeyframe();
        k2.time = 2f; k2.translation = new Super3DX.Vec3(0, 0, 0);
        animClip.keyframes[0].add(k2);
        // Bone 1 rotation keyframes
        Super3DX.AnimationKeyframe r0 = new Super3DX.AnimationKeyframe();
        r0.time = 0; r0.rotation = new Super3DX.Vec3(0, 0, 0);
        animClip.keyframes[1].add(r0);
        Super3DX.AnimationKeyframe r1 = new Super3DX.AnimationKeyframe();
        r1.time = 1f; r1.rotation = new Super3DX.Vec3(0.5f, 0, 0);
        animClip.keyframes[1].add(r1);
        Super3DX.AnimationKeyframe r2 = new Super3DX.AnimationKeyframe();
        r2.time = 2f; r2.rotation = new Super3DX.Vec3(0, 0, 0);
        animClip.keyframes[1].add(r2);

        animMesh = Super3DX.Mesh.createTexturedCube(0.8f, checkerTex);
        for (Super3DX.Vertex v : animMesh.vertices) {
            v.boneWeights[0] = 1f;
            v.boneIndices[0] = 0;
        }
    }

    private Super3DX.Mesh createGroundPlane(float size, Super3DX.Texture tex) {
        float h = size / 2;
        Super3DX.Vec3[] verts = {
            new Super3DX.Vec3(-h, -1.5f, -h), new Super3DX.Vec3(h, -1.5f, -h),
            new Super3DX.Vec3( h, -1.5f,  h), new Super3DX.Vec3(-h, -1.5f,  h)
        };
        Super3DX.Vec3 normal = new Super3DX.Vec3(0, 1, 0);
        Super3DX.Vertex[] vertices = new Super3DX.Vertex[4];
        float[][] uv = {{0,0},{1,0},{1,1},{0,1}};
        for (int i = 0; i < 4; i++) {
            vertices[i] = new Super3DX.Vertex(verts[i], normal, Color.WHITE, uv[i][0], uv[i][1]);
        }
        return new Super3DX.Mesh(vertices, new int[]{0,1,2,0,2,3});
    }

    @Override
    public void run() {
        long lastUpdate = System.nanoTime();
        while (!stopRequested) {
            long now = System.nanoTime();
            float dt = (now - lastUpdate) / 1e9f;
            lastUpdate = now;

            if (running) {
                angleX += 0.015f;
                angleY += 0.02f;
                if (animating) animTime += dt;

                if (particles) {
                    emitter.update(dt);
                }

                render();
                repaint();

                frameCount++;
                if (now - lastFpsTime > 1000000000L) {
                    fps = frameCount;
                    frameCount = 0;
                    lastFpsTime = now;
                }
            }
            try { Thread.sleep(16); } catch (InterruptedException e) { break; }
        }
    }

    private void render() {
        engine.clear(new Color(68, 136, 170));

        if (skybox) engine.renderSkybox(skyTex, 1f);

        engine.setCamera(
            new Super3DX.Vec3(0, 0, 4.5f),
            new Super3DX.Vec3(0, 0, 0),
            new Super3DX.Vec3(0, 1, 0)
        );

        if (fog) engine.setFog(new Color(68, 136, 170), 3f, 8f);
        else engine.disableFog();

        engine.setGammaCorrection(gamma);
        engine.setRenderMode(wireframe ? Super3DX.RenderMode.WIREFRAME : Super3DX.RenderMode.SOLID);

        // Main cube transform
        Super3DX.Matrix4x4 transform = new Super3DX.Matrix4x4();
        transform.rotateX(angleX);
        transform.rotateY(angleY);

        // Shadow pass
        engine.renderShadowDepth(cube, transform);

        // Main render with optional normal mapping
        if (normalMap) {
            engine.renderMesh(cube, transform, checkerTex, normalMapTex);
        } else {
            engine.renderMesh(cube, transform, checkerTex);
        }

        // Ground
        engine.renderMesh(ground, new Super3DX.Matrix4x4(), groundTex);

        // Animated cube (bobbing)
        if (animating) {
            Super3DX.Matrix4x4 animMat = new Super3DX.Matrix4x4();
            animMat.translate(-2f, 0, 0);
            engine.renderAnimatedMesh(animMesh, animMat, skeleton, animClip, animTime, checkerTex);
        }

        // Particles
        if (particles) engine.renderParticles(emitter, particleTex);

        // Billboard example
        Super3DX.Matrix4x4 billMat = new Super3DX.Matrix4x4();
        billMat.translate(2f, 0.5f, 0);
        engine.renderMesh(cube, billMat, checkerTex);

        // Post-process: bloom then FXAA
        if (bloom) engine.applyBloom(0.6f, 2);
        if (fxaa) engine.applyFXAA();

        // HUD
        int y = 20;
        engine.drawText("FPS: " + fps, 10, y, Color.WHITE); y += 20;
        engine.drawText("Mode: " + modeStr, 10, y, Color.YELLOW); y += 20;
        engine.drawText("[1] Wireframe [" + (wireframe ? "X" : " ") + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("[2] Gamma     [" + (gamma ? "X" : " ") + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("[3] Bloom     [" + (bloom ? "X" : " ") + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("[4] Fog       [" + (fog ? "X" : " ") + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("[5] NormalMap [" + (normalMap ? "X" : " ") + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("[6] FXAA      [" + (fxaa ? "X" : " ") + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("[7] Particles [" + (particles ? "X" : " ") + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("[8] Skybox    [" + (skybox ? "X" : " ") + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("[9] Animation [" + (animating ? "X" : " ") + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("[0] Reset view" , 10, y, Color.CYAN); y += 18;
        engine.drawText("SPACE: Pause  ESC: Exit", 10, y, Color.WHITE);
    }

    @Override
    public void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.drawImage(engine.getImage(), 0, 0, null);
    }

    @Override
    public void keyPressed(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_SPACE: running = !running; break;
            case KeyEvent.VK_ESCAPE: stopRequested = true; System.exit(0); break;
            case KeyEvent.VK_1: wireframe = !wireframe; modeStr = wireframe ? "WIREFRAME" : "SOLID"; break;
            case KeyEvent.VK_2: gamma = !gamma; break;
            case KeyEvent.VK_3: bloom = !bloom; break;
            case KeyEvent.VK_4: fog = !fog; break;
            case KeyEvent.VK_5: normalMap = !normalMap; break;
            case KeyEvent.VK_6: fxaa = !fxaa; break;
            case KeyEvent.VK_7: particles = !particles; if (!particles) emitter.particles.clear(); break;
            case KeyEvent.VK_8: skybox = !skybox; break;
            case KeyEvent.VK_9: animating = !animating; animTime = 0; break;
            case KeyEvent.VK_0: angleX = 0; angleY = 0; break;
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {}
    @Override
    public void keyTyped(KeyEvent e) {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Super3DX - Feature Demo");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            Super3DXTest panel = new Super3DXTest();
            frame.add(panel);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
