import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class Super3DXTest extends JPanel implements Runnable {
    private Super3DX engine;
    private Super3DX.Mesh cube, ground;
    private Super3DX.Texture checkerTex, groundTex, normalMapTex, skyTex, metalTex;
    private Super3DX.ParticleEmitter emitter;
    private Super3DX.Texture particleTex;
    private Super3DX.Skeleton skeleton;
    private Super3DX.AnimationClip animClip;
    private Super3DX.Mesh animMesh;
    private float angleX = 0, angleY = 0, animTime = 0;
    private boolean running = true, wireframe = false, gamma = false, bloom = false;
    private boolean fog = false, fxaa = false, particles = true, normalMap = false, skybox = true;
    private boolean animating = false, useSceneGraph = false, usePhysics = false, useTiles = false;
    private boolean useShader = false;
    private int fps = 0, frameCount = 0;
    private long lastFpsTime = System.nanoTime();
    private Thread renderThread;
    private boolean stopRequested = false;

    // Scene graph
    private Super3DX.Node rootNode;

    // Physics
    private Super3DX.RigidBody[] physicsBodies;
    private Super3DX.Vec3 gravity = new Super3DX.Vec3(0, -3f, 0);
    private Super3DX.Mesh sphereMesh;

    // Input
    private final Super3DX.Input input = new Super3DX.Input();

    // Audio
    private final Super3DX.Audio audio = new Super3DX.Audio();

    // Vertex pool
    private final Super3DX.VertexPool vertPool = new Super3DX.VertexPool();

    // Camera
    private float camDist = 4.5f, camAngle = 0, camHeight = 0;

    public Super3DXTest() {
        setPreferredSize(new Dimension(800, 600));
        setFocusable(true);
        addKeyListener(input.keyAdapter());
        addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent e) { handleToggle(e.getKeyCode()); }
        });
        addMouseListener(input.mouseAdapter());
        addMouseMotionListener(input.mouseAdapter());

        engine = new Super3DX(800, 600);
        engine.setBackfaceCulling(true);
        engine.setLighting(0.3f, 0.7f);
        engine.setSpecular(0.6f, 32f);
        engine.setLightDirection(new Super3DX.Vec3(0.5f, -0.7f, 0.3f));

        skyTex = Super3DX.Texture.createCheckerboard(256, 256, 32, new Color(30, 40, 70), new Color(60, 80, 120));
        checkerTex = Super3DX.Texture.createCheckerboard(64, 64, 8, new Color(255, 50, 50), new Color(50, 150, 255));
        groundTex = Super3DX.Texture.createCheckerboard(64, 64, 8, new Color(100, 100, 100), new Color(60, 60, 60));
        metalTex = Super3DX.Texture.createCheckerboard(64, 64, 4, new Color(180, 160, 100), new Color(120, 100, 60));
        normalMapTex = createBumpNormalMap(64, 64);
        particleTex = createParticleTexture(16, 16);

        cube = Super3DX.Mesh.createTexturedCube(1.5f, checkerTex);
        ground = createGroundPlane(8, groundTex);
        sphereMesh = createSphere(0.4f, 8);

        emitter = new Super3DX.ParticleEmitter();
        emitter.position = new Super3DX.Vec3(0, 0.5f, 0);
        emitter.spawnRate = 30f;
        emitter.particleLife = 2f;
        emitter.particleSize = 0.12f;
        emitter.speed = 1.2f;
        emitter.spread = 1.5f;
        emitter.startColor = new Color(255, 200, 50);
        emitter.endColor = new Color(255, 50, 50, 0);

        setupAnimation();
        setupSceneGraph();

        engine.enableShadows(256);
        engine.setLightMatrix(new Super3DX.Vec3(2, 4, 3), new Super3DX.Vec3(0, 0, 0));

        lastFpsTime = System.nanoTime();
        renderThread = new Thread(this);
        renderThread.start();
    }

    private Super3DX.Texture createBumpNormalMap(int w, int h) {
        Super3DX.Texture tex = new Super3DX.Texture(w, h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float nx = (float)Math.sin(x * 0.3f) * (float)Math.cos(y * 0.2f);
            float ny = (float)Math.sin(y * 0.3f) * (float)Math.cos(x * 0.2f);
            float nz = (float)Math.sqrt(Math.max(0, 1 - nx*nx - ny*ny));
            tex.pixels[y * w + x] = ((int)((nx * 0.5f + 0.5f) * 255) << 16) | ((int)((ny * 0.5f + 0.5f) * 255) << 8) | (int)(nz * 255);
        }
        tex.generateMipmaps();
        return tex;
    }

    private Super3DX.Texture createParticleTexture(int w, int h) {
        Super3DX.Texture tex = new Super3DX.Texture(w, h);
        int cx = w/2, cy = h/2, r = w/2;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float dist = (float)Math.sqrt((x-cx)*(x-cx) + (y-cy)*(y-cy));
            int alpha = (int)(Math.max(0, 1 - dist / r) * 255);
            tex.pixels[y * w + x] = (alpha << 24) | 0xFFFFFF;
        }
        return tex;
    }

    private Super3DX.Mesh createSphere(float radius, int segments) {
        int stacks = segments / 2;
        java.util.List<Super3DX.Vertex> verts = new java.util.ArrayList<>();
        java.util.List<Integer> idx = new java.util.ArrayList<>();
        for (int j = 0; j <= stacks; j++) {
            float theta = (float)(j * Math.PI / stacks);
            for (int i = 0; i <= segments; i++) {
                float phi = (float)(i * 2 * Math.PI / segments);
                float x = (float)(Math.sin(theta) * Math.cos(phi)) * radius;
                float y = (float)(Math.cos(theta)) * radius;
                float z = (float)(Math.sin(theta) * Math.sin(phi)) * radius;
                Super3DX.Vec3 n = new Super3DX.Vec3(x, y, z).normalize();
                float u = (float)i / segments, v = (float)j / stacks;
                verts.add(new Super3DX.Vertex(new Super3DX.Vec3(x, y, z), n, Color.WHITE, u, v));
            }
        }
        for (int j = 0; j < stacks; j++) for (int i = 0; i < segments; i++) {
            int a = j * (segments + 1) + i, b = a + segments + 1;
            idx.add(a); idx.add(b); idx.add(a + 1);
            idx.add(a + 1); idx.add(b); idx.add(b + 1);
        }
        int[] arr = new int[idx.size()]; for (int i = 0; i < idx.size(); i++) arr[i] = idx.get(i);
        return new Super3DX.Mesh(verts.toArray(new Super3DX.Vertex[0]), arr);
    }

    private void setupAnimation() {
        skeleton = new Super3DX.Skeleton(2);
        skeleton.parentIndices[0] = -1;
        skeleton.parentIndices[1] = 0;
        animClip = new Super3DX.AnimationClip(2, 2f);
        Super3DX.AnimationKeyframe k0 = new Super3DX.AnimationKeyframe();
        k0.time = 0; k0.translation = new Super3DX.Vec3(0, 0, 0);
        animClip.keyframes[0].add(k0);
        Super3DX.AnimationKeyframe k1 = new Super3DX.AnimationKeyframe();
        k1.time = 1f; k1.translation = new Super3DX.Vec3(0, 0.3f, 0);
        animClip.keyframes[0].add(k1);
        Super3DX.AnimationKeyframe k2 = new Super3DX.AnimationKeyframe();
        k2.time = 2f; k2.translation = new Super3DX.Vec3(0, 0, 0);
        animClip.keyframes[0].add(k2);
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
        for (Super3DX.Vertex v : animMesh.vertices) { v.boneWeights[0] = 1f; v.boneIndices[0] = 0; }
    }

    private void setupSceneGraph() {
        rootNode = new Super3DX.Node("root");
        Super3DX.Node child1 = new Super3DX.Node("cube1").setMesh(cube).setTexture(checkerTex);
        child1.translate(3, 0, 0);
        rootNode.addChild(child1);
        Super3DX.Node child2 = new Super3DX.Node("cube2").setMesh(cube).setTexture(metalTex);
        child2.translate(-3, 0, 0);
        rootNode.addChild(child2);
        Super3DX.Node grandchild = new Super3DX.Node("orbiter").setMesh(sphereMesh).setTexture(metalTex);
        grandchild.translate(0, 1.2f, 0);
        child1.addChild(grandchild);
    }

    private void setupPhysics() {
        physicsBodies = new Super3DX.RigidBody[6];
        physicsBodies[0] = new Super3DX.RigidBody(new Super3DX.Vec3(0, 3, 0), new Super3DX.Vec3(0.7f, 0.7f, 0.7f));
        physicsBodies[1] = new Super3DX.RigidBody(new Super3DX.Vec3(1, 4, 0.5f), new Super3DX.Vec3(0.7f, 0.7f, 0.7f));
        physicsBodies[2] = new Super3DX.RigidBody(new Super3DX.Vec3(-1, 5, -0.5f), new Super3DX.Vec3(0.7f, 0.7f, 0.7f));
        physicsBodies[3] = new Super3DX.RigidBody(new Super3DX.Vec3(0.5f, 6, 0), new Super3DX.Vec3(0.7f, 0.7f, 0.7f));
        physicsBodies[4] = new Super3DX.RigidBody(new Super3DX.Vec3(-0.5f, 7, 0), new Super3DX.Vec3(0.7f, 0.7f, 0.7f));
        physicsBodies[5] = new Super3DX.RigidBody(new Super3DX.Vec3(0, 8, 0), new Super3DX.Vec3(0.7f, 0.7f, 0.7f));
        for (Super3DX.RigidBody rb : physicsBodies) rb.restitution = 0.4f;
    }

    private Super3DX.Mesh createGroundPlane(float size, Super3DX.Texture tex) {
        float h = size / 2;
        Super3DX.Vec3[] verts = { new Super3DX.Vec3(-h, -1.5f, -h), new Super3DX.Vec3(h, -1.5f, -h), new Super3DX.Vec3(h, -1.5f, h), new Super3DX.Vec3(-h, -1.5f, h) };
        Super3DX.Vec3 normal = new Super3DX.Vec3(0, 1, 0);
        Super3DX.Vertex[] vertices = new Super3DX.Vertex[4];
        float[][] uv = {{0,0},{1,0},{1,1},{0,1}};
        for (int i = 0; i < 4; i++) vertices[i] = new Super3DX.Vertex(verts[i], normal, Color.WHITE, uv[i][0], uv[i][1]);
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
                updateInput(dt);
                angleX += 0.015f;
                angleY += 0.02f;
                if (animating) animTime += dt;
                if (particles) emitter.update(dt);
                if (usePhysics && physicsBodies != null) updatePhysics(dt);
                render();
                repaint();

                if (input.key(KeyEvent.VK_A)) audio.playClick();
                if (input.key(KeyEvent.VK_S)) audio.playHit();

                frameCount++;
                if (now - lastFpsTime > 1000000000L) {
                    fps = frameCount;
                    frameCount = 0;
                    lastFpsTime = now;
                }
            }
            try { Thread.sleep(16); } catch (InterruptedException e) { break; }
        }
        audio.close();
    }

    private void updateInput(float dt) {
        input.update();
        float speed = 2f * dt;
        if (input.key(KeyEvent.VK_W) || input.key(KeyEvent.VK_UP)) camDist -= speed;
        if (input.key(KeyEvent.VK_S) || input.key(KeyEvent.VK_DOWN)) camDist += speed;
        if (input.key(KeyEvent.VK_A) || input.key(KeyEvent.VK_LEFT)) camAngle -= speed;
        if (input.key(KeyEvent.VK_D) || input.key(KeyEvent.VK_RIGHT)) camAngle += speed;
        if (input.key(KeyEvent.VK_Q)) camHeight += speed;
        if (input.key(KeyEvent.VK_E)) camHeight -= speed;
        camDist = Math.max(2, Math.min(15, camDist));
    }

    private void updatePhysics(float dt) {
        Super3DX.Vec3 groundPlane = new Super3DX.Vec3(0, -1.5f, 0);
        boolean hit = false;
        for (Super3DX.RigidBody rb : physicsBodies) {
            rb.update(dt, gravity);
            if (rb.position.y < -1.2f) { rb.position.y = -1.2f; rb.velocity.y *= -rb.restitution; if (Math.abs(rb.velocity.y) < 0.1f) rb.velocity.y = 0; }
            for (Super3DX.RigidBody other : physicsBodies) { if (rb != other && rb.collide(other)) hit = true; }
        }
        if (hit) audio.playClick();
    }

    private void render() {
        engine.clear(new Color(68, 136, 170));
        if (skybox) engine.renderSkybox(skyTex, 1f);

        float cx = (float)(Math.sin(camAngle) * camDist);
        float cz = (float)(Math.cos(camAngle) * camDist);
        engine.setCamera(new Super3DX.Vec3(cx, camHeight, cz), new Super3DX.Vec3(0, 0, 0), new Super3DX.Vec3(0, 1, 0));

        if (fog) engine.setFog(new Color(68, 136, 170), 3f, 8f);
        else engine.disableFog();
        engine.setGammaCorrection(gamma);
        engine.setRenderMode(wireframe ? Super3DX.RenderMode.WIREFRAME : Super3DX.RenderMode.SOLID);

        if (useTiles) engine.enableTileBased(true, 32);

        // Shadow pass for main cube
        Super3DX.Matrix4x4 transform = new Super3DX.Matrix4x4();
        transform.rotateX(angleX); transform.rotateY(angleY);
        engine.renderShadowDepth(cube, transform);

        // Scene graph mode vs legacy mode
        if (useSceneGraph) {
            rootNode.updateWorld(new Super3DX.Matrix4x4());
            rootNode.children.get(0).children.get(0).localTransform.translate(0, (float)Math.sin(animTime * 2) * 0.5f, 0);
            engine.renderNode(rootNode);
        } else {
            if (useShader) {
                Super3DX.ShaderProgram shader = new Super3DX.ShaderProgram(
                    (pos, norm, uv, col) -> {
                        float wave = (float)Math.sin(pos.y * 2 + animTime * 3) * 0.1f;
                        return new Super3DX.Vec4(pos.x, pos.y + wave, pos.z, pos.w);
                    },
                    (bar, wp, n, uv, col) -> {
                        float r = (float)((bar.x * 255) % 256);
                        float g = (float)((bar.y * 255) % 256);
                        float b = (float)((bar.z * 255) % 256);
                        return ((int)r << 16) | ((int)g << 8) | (int)b;
                    }
                ).texture(checkerTex);
                engine.renderWithShader(cube, transform, shader);
            } else {
                if (normalMap) engine.renderMesh(cube, transform, checkerTex, normalMapTex);
                else engine.renderMesh(cube, transform, checkerTex);
            }
        }

        // Ground
        engine.renderMesh(ground, new Super3DX.Matrix4x4(), groundTex);

        // Physics bodies
        if (usePhysics && physicsBodies != null) {
            for (int i = 0; i < physicsBodies.length; i++) {
                Super3DX.RigidBody rb = physicsBodies[i];
                Super3DX.Matrix4x4 m = new Super3DX.Matrix4x4();
                m.translate(rb.position.x, rb.position.y, rb.position.z);
                m.scale(0.8f, 0.8f, 0.8f);
                engine.renderMesh(sphereMesh, m, metalTex);
            }
        }

        // Animated cube
        if (animating) {
            Super3DX.Matrix4x4 animMat = new Super3DX.Matrix4x4();
            animMat.translate(-2f, 0, 0);
            engine.renderAnimatedMesh(animMesh, animMat, skeleton, animClip, animTime, checkerTex);
        }

        // Particles
        if (particles) engine.renderParticles(emitter, particleTex);

        // Billboard
        Super3DX.Matrix4x4 billMat = new Super3DX.Matrix4x4();
        billMat.translate(2f, 0.5f, 0);
        engine.renderMesh(cube, billMat, checkerTex);

        // Flush tiles if in tile mode
        if (useTiles) engine.renderTileBased();

        // Post-process
        if (bloom) engine.applyBloom(0.6f, 2);
        if (fxaa) engine.applyFXAA();

        // HUD
        int y = 20;
        engine.drawText("FPS: " + fps, 10, y, Color.WHITE); y += 20;
        engine.drawText("WASD/Arrows: move camera  Q/E: height", 10, y, Color.GREEN); y += 16;
        engine.drawText("[1] Wireframe [" + yn(wireframe) + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("[2] Gamma     [" + yn(gamma) + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("[3] Bloom     [" + yn(bloom) + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("[4] Fog       [" + yn(fog) + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("[5] NormalMap [" + yn(normalMap) + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("[6] FXAA      [" + yn(fxaa) + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("[7] Particles [" + yn(particles) + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("[8] Skybox    [" + yn(skybox) + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("[9] Animation [" + yn(animating) + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("[0] SceneGraph[" + yn(useSceneGraph) + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("[-] Physics  [" + yn(usePhysics) + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("[=] Shader   [" + yn(useShader) + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("[/] Tiles    [" + yn(useTiles) + "]", 10, y, Color.CYAN); y += 18;
        engine.drawText("P VertPool: " + vertPool.active() + "/" + vertPool.total(), 10, y, Color.LIGHT_GRAY); y += 18;
        engine.drawText("SPACE: Pause  ESC: Exit", 10, y, Color.WHITE);
    }

    private String yn(boolean v) { return v ? "X" : " "; }

    private void handleToggle(int code) {
        switch (code) {
            case KeyEvent.VK_SPACE: running = !running; return;
            case KeyEvent.VK_ESCAPE: stopRequested = true; System.exit(0); return;
            case KeyEvent.VK_1: wireframe = !wireframe; return;
            case KeyEvent.VK_2: gamma = !gamma; return;
            case KeyEvent.VK_3: bloom = !bloom; return;
            case KeyEvent.VK_4: fog = !fog; return;
            case KeyEvent.VK_5: normalMap = !normalMap; return;
            case KeyEvent.VK_6: fxaa = !fxaa; return;
            case KeyEvent.VK_7: particles = !particles; if (!particles) emitter.particles.clear(); audio.playClick(); return;
            case KeyEvent.VK_8: skybox = !skybox; return;
            case KeyEvent.VK_9: animating = !animating; animTime = 0; return;
            case KeyEvent.VK_0: useSceneGraph = !useSceneGraph; audio.playClick(); return;
            case KeyEvent.VK_MINUS: usePhysics = !usePhysics; if (usePhysics) setupPhysics(); audio.playClick(); return;
            case KeyEvent.VK_EQUALS: useShader = !useShader; audio.playClick(); return;
            case KeyEvent.VK_SLASH: useTiles = !useTiles; engine.enableTileBased(useTiles, 32); audio.playClick(); return;
        }
    }

    @Override
    public void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.drawImage(engine.getImage(), 0, 0, null);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Super3DX - Pro Demo");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            Super3DXTest panel = new Super3DXTest();
            frame.add(panel);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
