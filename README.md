# Super3DX

A professional-grade software 3D renderer in a single Java class. No dependencies beyond the JDK. No native files, no GPU — all rendering is pure Java.

## Setup
```
javac Super3DX.java
java Super3DXTest
```

Or recompile from source: `javac Super3DX.java && jar cf Super3DX.jar Super3DX.class`

## Demo Controls

| Key | Feature |
|-----|---------|
| `WASD` / Arrows | Orbit camera |
| `Q` / `E` | Raise / lower camera |
| `SPACE` | Pause / resume |
| `ESC` | Exit |
| `1` | Wireframe toggle |
| `2` | Gamma correction |
| `3` | Bloom |
| `4` | Fog |
| `5` | Normal mapping |
| `6` | FXAA |
| `7` | Particles |
| `8` | Skybox |
| `9` | Skeletal animation |
| `0` | Scene graph toggle |
| `-` | Physics simulation |
| `=` | Custom shader |
| `/` | Tile-based multithreaded rendering |
| `A` | Audio click |
| `S` | Audio hit |

## Complete API Reference

### Constructor

| Method | Description |
|--------|-------------|
| `new Super3DX(int width, int height)` | Create renderer with specified resolution |

---

### Renderer Methods

| Method | Description |
|--------|-------------|
| `clear(int color)` | Clear framebuffer and depth buffer with RGB color |
| `clear(Color color)` | Clear framebuffer and depth buffer with Color object |
| `setCamera(Vec3 position, Vec3 target, Vec3 up)` | Set camera view matrix using look-at |
| `setPerspective(float fovDeg, float aspect, float near, float far)` | Set perspective projection matrix |
| `setBackfaceCulling(boolean cull)` | Enable/disable backface culling |
| `setCullMode(CullMode mode)` | Set cull mode: NONE, FRONT, BACK |
| `setBlendMode(BlendMode mode)` | Set blend mode: NONE, ALPHA, ADDITIVE |
| `setRenderMode(RenderMode mode)` | Set render mode: SOLID, WIREFRAME, SOLID_WIREFRAME |
| `setGammaCorrection(boolean enable)` | Enable/disable gamma correction (pow 1/2.2) |
| `setLightDirection(Vec3 dir)` | Set directional light direction |
| `setLighting(float ambient, float diffuse)` | Set ambient and diffuse intensities |
| `setSpecular(float intensity, float shininess)` | Set specular intensity and shininess |
| `setDayNightCycle(float timeOfDay)` | Animate sun position (0-1) with automatic lighting |
| `setFog(Color color, float near, float far)` | Enable linear fog with color and distance range |
| `disableFog()` | Disable fog rendering |
| `toggleWireframe()` | Toggle between SOLID and WIREFRAME modes |
| `getPixels()` | Get raw framebuffer pixel array (int[]) |
| `getImage()` | Get current frame as BufferedImage |
| `saveScreenshot()` | Save screenshot with timestamp filename |
| `saveScreenshot(String filename)` | Save screenshot to specified file |

---

### Rendering Methods

| Method | Description |
|--------|-------------|
| `renderMesh(Mesh mesh, Matrix4x4 worldMatrix)` | Render untextured mesh |
| `renderMesh(Mesh mesh, Matrix4x4 worldMatrix, Texture texture)` | Render textured mesh |
| `renderMesh(Mesh mesh, Matrix4x4 worldMatrix, Texture texture, Texture normalMap)` | Render with normal mapping |
| `renderInstanced(Mesh mesh, Matrix4x4[] transforms, Texture texture)` | Render multiple instances with different transforms |
| `renderBillboard(Texture texture, Vec3 position, float size)` | Render camera-facing billboard |
| `renderSkybox(Texture texture)` | Render skybox using inverse view-projection |
| `renderSkybox(Texture texture, float size)` | Render skybox with size factor |
| `renderParticles(ParticleEmitter emitter, Texture texture)` | Render particle system (auto-billboarded) |
| `renderAnimatedMesh(Mesh mesh, Matrix4x4 worldMatrix, Skeleton skeleton, AnimationClip clip, float time, Texture texture)` | Render skinned animated mesh |
| `renderShadowDepth(Mesh mesh, Matrix4x4 worldMatrix)` | Render shadow depth map for shadow mapping |
| `drawLine(int x0, int y0, int x1, int y1, Color color)` | Draw line on screen |
| `drawText(String text, int x, int y, Color color)` | Draw text on screen |
| `renderInstancedGL(Mesh mesh, InstanceData instances, Texture texture)` | Render instances from InstanceData |
| `renderInstancedBatch(Mesh mesh, InstanceData instances, Texture texture)` | Batch-render instances (100 per batch) |

---

### Shadow Mapping Methods

| Method | Description |
|--------|-------------|
| `enableShadows(int size)` | Enable shadow mapping with specified map size |
| `clearShadowMap()` | Clear shadow depth buffer |
| `setShadowBias(float bias)` | Set shadow bias to prevent self-shadowing |
| `setShadowIntensity(float intensity)` | Set shadow darkness (0-1) |
| `setLightMatrix(Vec3 lightPos, Vec3 lightTarget)` | Set light view-projection matrix |
| `getShadowMapImage()` | Get debug visualization of shadow map as BufferedImage |

---

### Post-Processing Methods

| Method | Description |
|--------|-------------|
| `applyBloom(float threshold, int passes)` | Apply bloom effect with intensity threshold and blur passes |
| `applyFXAA()` | Apply Fast Approximate Anti-Aliasing |
| `applySepia()` | Apply sepia tone color filter |
| `applyVignette(float intensity)` | Apply vignette effect (0-1) |
| `applyPixelate(int pixelSize)` | Apply pixelation effect |
| `enableHDR()` | Enable HDR rendering (float buffer) |
| `disableHDR()` | Disable HDR rendering |
| `setExposure(float exposure)` | Set exposure for HDR tone mapping |
| `setTonemapMode(int mode)` | Set tone mapping mode: 0=Reinhard, 1=ACES, 2=Uncharted |
| `tonemap()` | Apply tone mapping to HDR buffer |

---

### Vertex & Math Types

#### Vec3

| Method | Description |
|--------|-------------|
| `new Vec3()` | Zero vector |
| `new Vec3(float x, float y, float z)` | Create vector |
| `add(Vec3 other)` | Vector addition |
| `sub(Vec3 other)` | Vector subtraction |
| `scale(float s)` | Scalar multiplication |
| `dot(Vec3 other)` | Dot product |
| `cross(Vec3 other)` | Cross product |
| `length()` | Vector magnitude |
| `normalize()` | Normalized vector |
| `clone()` | Copy vector |

#### Vec4

| Method | Description |
|--------|-------------|
| `new Vec4()` | Zero vector (w=1) |
| `new Vec4(float x, float y, float z, float w)` | Create vector |
| `clone()` | Copy vector |

#### Matrix4x4

| Method | Description |
|--------|-------------|
| `new Matrix4x4()` | Identity matrix |
| `identity()` | Reset to identity |
| `perspective(float fovDeg, float aspect, float near, float far)` | Perspective projection |
| `lookAt(Vec3 eye, Vec3 target, Vec3 up)` | View matrix from camera |
| `translate(float x, float y, float z)` | Apply translation |
| `rotateX(float angle)` | Apply X-axis rotation (radians) |
| `rotateY(float angle)` | Apply Y-axis rotation (radians) |
| `rotateZ(float angle)` | Apply Z-axis rotation (radians) |
| `scale(float x, float y, float z)` | Apply scaling |
| `mul(Matrix4x4 a, Matrix4x4 b)` | Matrix multiplication (this = a * b) |
| `invert()` | Invert matrix in-place |
| `clone()` | Copy matrix |
| `getArray()` | Get 16-element float array (column-major) |

---

### Mesh (Super3DX.Mesh)

| Method | Description |
|--------|-------------|
| `new Mesh(Vertex[] vertices, int[] indices)` | Create mesh from vertices and triangle indices |
| `Mesh.createCube(float size, Color color)` | Create solid color cube |
| `Mesh.createTexturedCube(float size, Texture texture)` | Create textured cube |
| `Mesh.loadOBJ(String filename)` | Load OBJ file (supports quads, ngons, MTL) |
| `Mesh.loadMTL(String filename)` | Load MTL material library |
| `saveBindPose()` | Save bind pose for skeletal animation |

**Fields:**
- `Vertex[] vertices` - Array of vertices
- `int[] indices` - Triangle indices (3 per triangle)
- `int numTriangles` - Number of triangles
- `Map<String, Material> materials` - Material library
- `Vec4[] bindPositions` - Bind pose positions (for skinning)
- `Vec3[] bindNormals` - Bind pose normals (for skinning)

---

### Vertex (Super3DX.Vertex)

**Fields:**
- `Vec4 position` - Position (x,y,z,w)
- `Vec3 normal` - Normal vector
- `Color color` - Vertex color
- `float u, v` - Texture coordinates
- `int[] boneIndices` - Bone indices for skinning (size 4)
- `float[] boneWeights` - Bone weights for skinning (size 4)

---

### Texture (Super3DX.Texture)

| Method | Description |
|--------|-------------|
| `new Texture(int width, int height)` | Create empty texture |
| `new Texture(int width, int height, int color)` | Create texture filled with color |
| `Texture.createCheckerboard(int width, int height, int tileSize, Color c1, Color c2)` | Create checkerboard pattern |
| `Texture.load(String filename)` | Load image from file (PNG, JPG, etc.) |
| `generateMipmaps()` | Generate mipmap chain |
| `sample(float u, float v, float lod)` | Sample texture with mipmapping and bilinear filtering |

**Fields:**
- `int[] pixels` - Raw pixel data (ARGB)
- `int width, height` - Texture dimensions
- `Texture[] mipmaps` - Mipmap chain
- `int numMipLevels` - Number of mip levels

---

### Material (Super3DX.Material)

**Fields:**
- `String name` - Material name
- `Color diffuse` - Diffuse color
- `Color specular` - Specular color
- `float shininess` - Shininess exponent
- `String texturePath` - Path to texture file
- `Texture texture` - Loaded texture

---

### Skeletal Animation

#### Skeleton (Super3DX.Skeleton)

| Method | Description |
|--------|-------------|
| `new Skeleton(int numBones)` | Create skeleton with bone count |

**Fields:**
- `int numBones` - Number of bones
- `Matrix4x4[] boneTransforms` - Current bone transforms
- `Matrix4x4[] inverseBindPose` - Inverse bind pose matrices
- `int[] parentIndices` - Parent bone indices (-1 for root)

#### AnimationKeyframe (Super3DX.AnimationKeyframe)

**Fields:**
- `float time` - Keyframe time
- `Vec3 translation` - Translation at keyframe
- `Vec3 rotation` - Rotation at keyframe (Euler angles)
- `Vec3 scale` - Scale at keyframe

#### AnimationClip (Super3DX.AnimationClip)

| Method | Description |
|--------|-------------|
| `new AnimationClip(int numBones, float duration)` | Create animation clip |

**Fields:**
- `String name` - Clip name
- `float duration` - Clip duration in seconds
- `List<AnimationKeyframe>[] keyframes` - Keyframes per bone

**Methods:**
- `sample(float time, Matrix4x4[] outTransforms)` - Sample animation at time, fills output transforms

---

### Particles

#### Particle (Super3DX.Particle)

**Fields:**
- `Vec3 position` - Position
- `Vec3 velocity` - Velocity
- `Color color` - Current color
- `float size` - Current size
- `float life` - Remaining life
- `float maxLife` - Maximum life

#### ParticleEmitter (Super3DX.ParticleEmitter)

**Fields:**
- `Vec3 position` - Emitter position
- `List<Particle> particles` - Active particles
- `float spawnRate` - Particles per second
- `float particleLife` - Particle lifetime in seconds
- `float particleSize` - Base particle size
- `Color startColor` - Particle color at birth
- `Color endColor` - Particle color at death
- `float speed` - Emitted particle speed
- `float spread` - Emission spread angle
- `boolean active` - Whether emitter is active

**Methods:**
- `update(float dt)` - Update particle system (spawn and simulate)

---

### PBR (Physically Based Rendering)

#### PBRMaterial (Super3DX.PBRMaterial)

**Fields:**
- `Color albedo` - Base color
- `float metallic` - Metallic value (0-1)
- `float roughness` - Roughness value (0-1)
- `float ao` - Ambient occlusion
- `float emissive` - Emissive intensity
- `Texture albedoMap` - Albedo texture
- `Texture metallicMap` - Metallic texture
- `Texture roughnessMap` - Roughness texture
- `Texture aoMap` - Ambient occlusion texture
- `Texture normalMap` - Normal map texture
- `Texture emissiveMap` - Emissive texture

#### PBRRenderer (Super3DX.PBRRenderer)

| Method | Description |
|--------|-------------|
| `calculatePBR(Color albedo, float metallic, float roughness, Vec3 normal, Vec3 viewDir, Vec3 lightDir, Vec3 lightColor, float intensity)` | Calculate PBR lighting (GGX) |

---

### Deferred Rendering

#### GBuffer (Super3DX.GBuffer)

**Fields:**
- `int[] albedo` - Albedo color buffer
- `float[] normal` - Normal buffer (x,y,z)
- `float[] position` - Position buffer (x,y,z)
- `float[] metallic` - Metallic buffer
- `float[] roughness` - Roughness buffer
- `float[] depth` - Depth buffer
- `int width, height` - GBuffer dimensions

**Methods:**
- `new GBuffer(int width, int height)` - Create GBuffer
- `clear()` - Clear all buffers

**Renderer Methods:**
- `enableDeferredRendering()` - Enable deferred rendering
- `renderDeferred()` - Perform deferred lighting pass
- `setMetallicRoughness(float metallic, float roughness)` - Set metallic/roughness for current triangle

---

### GPU Instancing

#### InstanceData (Super3DX.InstanceData)

| Method | Description |
|--------|-------------|
| `new InstanceData(int count)` | Create instance data |

**Fields:**
- `Matrix4x4[] transforms` - Instance transforms
- `int[] colors` - Instance colors
- `int count` - Number of instances

**Renderer Methods:**
- `createInstanceGrid(int count, float spacing)` - Create grid of instances

---

### Compute Shaders (Emulation)

#### ComputeKernel (Super3DX.ComputeKernel)

| Method | Description |
|--------|-------------|
| `execute(int id)` | Execute kernel for a work item |

#### ComputeShader (Super3DX.ComputeShader)

| Method | Description |
|--------|-------------|
| `new ComputeShader(String name, int workGroupSize, ComputeKernel kernel)` | Create compute shader |
| `dispatch(int groups)` | Dispatch shader with specified work groups |

#### ComputeShaderManager (Super3DX.ComputeShaderManager)

| Method | Description |
|--------|-------------|
| `createShader(String name, int workGroupSize, ComputeKernel kernel)` | Create and register shader |
| `dispatch(String name, int groups)` | Dispatch registered shader |

---

### Shader Support (OpenGL Emulation)

#### Shader (Super3DX.Shader)

| Method | Description |
|--------|-------------|
| `new Shader(String vertexSource, String fragmentSource)` | Create shader program |
| `use()` | Activate shader |
| `setUniform(String name, float value)` | Set float uniform |
| `setUniform(String name, float[] matrix)` | Set matrix uniform |
| `setUniform(String name, int value)` | Set integer uniform |
| `getProgramID()` | Get program ID |

#### ShaderManager (Super3DX.ShaderManager)

| Method | Description |
|--------|-------------|
| `loadShader(String name, String vertexPath, String fragmentPath)` | Load shader from files |

**Renderer Method:**
- `setShader(String name)` - Activate shader by name

---

### Tile-Based Multithreaded Rendering

| Method | Description |
|--------|-------------|
| `enableTileBased(boolean enable, int tileSize)` | Enable/disable tiled rendering with tile size |
| `renderTileBased()` | Process all tile batches in parallel via ForkJoinPool |

Splits the screen into a grid of tiles (default 32×32). Each tile is processed independently with its own AABB-culled triangle list, enabling parallel rasterization across all available CPU cores.

---

### Scene Graph (Super3DX.Node)

| Method | Description |
|--------|-------------|
| `new Node(String name)` | Create root node |
| `addChild(Node child)` | Attach child node (returns this for chaining) |
| `translate(x, y, z)` | Apply local translation |
| `rotateX/Y/Z(angle)` | Apply local rotation (radians) |
| `scale(x, y, z)` | Apply local scaling |
| `setMesh(Mesh m)` | Attach mesh |
| `setTexture(Texture t)` | Attach diffuse texture |
| `updateWorld(Matrix4x4 parentWorld)` | Recompute world transforms recursively |
| `renderNode(Node node)` | Render a scene graph subtree |

**Fields:** `name`, `parent`, `children`, `localTransform`, `worldTransform`, `mesh`, `texture`, `normalMap`, `visible`

---

### Input System (Super3DX.Input)

| Method | Description |
|--------|-------------|
| `key(int code)` | Check if a key is held (KeyEvent VK_ codes) |
| `mouseButton(int btn)` | Check if mouse button is held |
| `update()` | Call once per frame to reset mouse deltas |
| `keyAdapter()` | Returns KeyAdapter to attach to your JPanel |
| `mouseAdapter()` | Returns MouseAdapter to attach to your JPanel |

**Fields:** `keys[512]`, `mouseButtons[8]`, `mouseX`, `mouseY`, `mouseDX`, `mouseDY`, `mouseInWindow`

---

### Physics (Super3DX.AABB, RigidBody)

#### AABB

| Method | Description |
|--------|-------------|
| `new AABB()` | Zero bounds |
| `new AABB(Vec3 min, Vec3 max)` | Initialize bounds |
| `centerExtents(Vec3 center, Vec3 extents)` | Build from center + half-size |
| `overlaps(AABB other)` | AABB overlap test |
| `center()` | Compute center point |

#### RigidBody

| Method | Description |
|--------|-------------|
| `new RigidBody(Vec3 position, Vec3 size)` | Create body with AABB |
| `update(float dt, Vec3 gravity)` | Integrate velocity and position |
| `collide(RigidBody other)` | Resolve collision with impulse response |

**Fields:** `position`, `velocity`, `bounds`, `mass`, `restitution`, `useGravity`, `isStatic`

---

### Shader Interface (Super3DX.VertexShader, FragmentShader, ShaderProgram)

```java
@FunctionalInterface VertexShader
    Vec4 process(Vec4 position, Vec3 normal, Vec2 uv, Color color)

@FunctionalInterface FragmentShader
    int process(Vec3 barycentric, Vec3 worldPos, Vec3 normal, Vec2 uv, Color color)
```

| ShaderProgram Method | Description |
|----------------------|-------------|
| `new ShaderProgram(VertexShader vs, FragmentShader fs)` | Create shader program |
| `texture(Texture t)` | Bind diffuse texture |
| `normalMap(Texture n)` | Bind normal map |

| Renderer Method | Description |
|-----------------|-------------|
| `renderWithShader(Mesh, Matrix4x4, ShaderProgram)` | Render mesh with custom shaders |

---

### Audio (Super3DX.Audio)

| Method | Description |
|--------|-------------|
| `new Audio()` | Open audio line (silent if unavailable) |
| `playTone(float freq, float duration, float volume)` | Play procedural sine wave tone |
| `playClick()` | Short click sound (1000Hz, 50ms) |
| `playHit()` | Impact sound (200Hz, 150ms) |
| `close()` | Drain and close audio line |

Uses `javax.sound.sampled.SourceDataLine` — no audio files needed.

---

### Object Pool (Super3DX.VertexPool)

| Method | Description |
|--------|-------------|
| `get()` | Retrieve or allocate a Vertex |
| `reset()` | Recycle all vertices |
| `active()` | Number of currently active vertices |
| `total()` | Total vertices in pool |

Eliminates per-frame allocation by reusing Vertex objects.

---

### Material (PBR) — Super3DX.Mat

| Method | Description |
|--------|-------------|
| `new Mat()` | Default material (non-metal, roughness 0.5) |
| `albedo(Vec3)` | Set albedo color |
| `metalness(float)` | Set metalness (0-1) |
| `roughness(float)` | Set roughness (0-1) |
| `texture(Texture)` | Set diffuse texture |
| `Mat.createDefault()` | Dielectric white material |
| `Mat.createMetal(Vec3 albedo, float roughness)` | Metallic material |
| `Mat.createDielectric(Vec3 albedo, float roughness)` | Non-metallic material |

**LightModel enum:** `BLINN_PHONG`, `PBR` (Cook-Torrance GGX)

| Renderer Method | Description |
|-----------------|-------------|
| `setLightModel(LightModel mode)` | Switch between Blinn-Phong and PBR |

---

### GBuffer (Deferred Shading) — Super3DX.GBuffer

**Fields:** `position[3]`, `normal[3]`, `albedo[]`, `depth[]`

| Renderer Method | Description |
|-----------------|-------------|
| `resolveDeferred()` | Execute deferred lighting pass |

Enable via `RendererConfig.deferred(true)` in constructor.

---

### Enums

| Enum | Values |
|------|--------|
| `BlendMode` | `NONE`, `ALPHA`, `ADDITIVE` |
| `CullMode` | `NONE`, `FRONT`, `BACK` |
| `RenderMode` | `SOLID`, `WIREFRAME`, `SOLID_WIREFRAME` |
| `ToneMapMode` | `NONE`, `REINHARD`, `ACES`, `UNREAL` |
| `LightModel` | `BLINN_PHONG`, `PBR` |

---

## Quick Examples

### Basic Setup
```java
Super3DX r = new Super3DX(800, 600);
r.clear(new Color(68, 136, 170));
r.setCamera(new Super3DX.Vec3(0, 0, 4.5f), new Super3DX.Vec3(0, 0, 0), new Super3DX.Vec3(0, 1, 0));
Super3DX.Texture tex = Super3DX.Texture.createCheckerboard(64, 64, 8, Color.RED, Color.BLUE);
Super3DX.Mesh cube = Super3DX.Mesh.createTexturedCube(1.5f, tex);
Super3DX.Matrix4x4 m = new Super3DX.Matrix4x4();
m.rotateY(0.5f);
r.renderMesh(cube, m, tex);
BufferedImage img = r.getImage();
```

### Scene Graph
```java
Super3DX.Node root = new Super3DX.Node("root");
Super3DX.Node child = new Super3DX.Node("child")
    .setMesh(cube).setTexture(tex)
    .translate(2, 0, 0)
    .rotateY(0.5f);
root.addChild(child);
root.updateWorld(new Super3DX.Matrix4x4());
r.renderNode(root);
```

### Custom Shaders
```java
Super3DX.ShaderProgram shader = new Super3DX.ShaderProgram(
    (pos, norm, uv, col) -> {
        float wave = (float)Math.sin(pos.y * 2) * 0.1f;
        return new Super3DX.Vec4(pos.x, pos.y + wave, pos.z, pos.w);
    },
    (bar, wp, n, uv, col) -> 0xFF00FF00 // solid green
);
r.renderWithShader(cube, m, shader);
```

### Physics
```java
Super3DX.RigidBody body = new Super3DX.RigidBody(
    new Super3DX.Vec3(0, 3, 0),
    new Super3DX.Vec3(0.5f, 0.5f, 0.5f));
Super3DX.Vec3 gravity = new Super3DX.Vec3(0, -3f, 0);
body.update(0.016f, gravity);
```

### Input + Audio
```java
Super3DX.Input input = new Super3DX.Input();
panel.addKeyListener(input.keyAdapter());
panel.addMouseListener(input.mouseAdapter());
if (input.key(KeyEvent.VK_SPACE)) { /* jump */ }
input.update(); // call once per frame

Super3DX.Audio audio = new Super3DX.Audio();
audio.playTone(440, 0.5f, 0.3f); // 440Hz for 0.5s
audio.close(); // cleanup
```