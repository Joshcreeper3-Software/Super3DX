# Super3DX

A minimal software 3D renderer in a single Java class. No dependencies beyond the JDK.

## Setup

```
javac -cp Super3DX.jar;. Super3DXTest.java
java -cp Super3DX.jar;. Super3DXTest
```

Or recompile from source: `javac Super3DX.java && jar cf Super3DX.jar Super3DX.class`

## Quick Start

```java
Super3DX r = new Super3DX(800, 600);
Super3DX.Texture tex = Super3DX.Texture.createCheckerboard(64, 64, 8, Color.RED, Color.BLUE);
Super3DX.Mesh cube = Super3DX.Mesh.createTexturedCube(1.5f, tex);

r.clear(new Color(68, 136, 170));
r.setCamera(new Super3DX.Vec3(0, 0, 4.5f), new Super3DX.Vec3(0, 0, 0), new Super3DX.Vec3(0, 1, 0));
Super3DX.Matrix4x4 m = new Super3DX.Matrix4x4();
m.rotateY(angle);
r.renderMesh(cube, m, tex);
BufferedImage img = r.getImage();
```

## Full API

### Renderer

| Method | Description |
|---|---|
| `Super3DX(int w, int h)` | Create renderer |
| `clear(Color)` / `clear(int rgb)` | Clear framebuffer & depth buffer |
| `setCamera(Vec3 eye, Vec3 target, Vec3 up)` | Camera look-at |
| `setPerspective(float fov, float aspect, float near, float far)` | Projection |
| `setBackfaceCulling(boolean)` | Enable/disable backface culling |
| `setCullMode(CullMode)` | `NONE`, `FRONT`, `BACK` |
| `setBlendMode(BlendMode)` | `NONE`, `ALPHA`, `ADDITIVE` |
| `setRenderMode(RenderMode)` | `SOLID`, `WIREFRAME`, `SOLID_WIREFRAME` |
| `setGammaCorrection(boolean)` | Enable/disable gamma correction (pow 1/2.2) |
| `setFog(Color, float near, float far)` | Enable linear fog |
| `disableFog()` | Disable fog |
| `applyBloom(float threshold, int passes)` | Bloom post-process |
| `applyFXAA()` | Fast approximate anti-aliasing post-process |

### Rendering

| Method | Description |
|---|---|
| `renderMesh(Mesh, Matrix4x4 world)` | Untextured mesh |
| `renderMesh(Mesh, Matrix4x4 world, Texture)` | Textured mesh |
| `renderMesh(Mesh, Matrix4x4 world, Texture, Texture normalMap)` | Normal-mapped mesh |
| `renderInstanced(Mesh, Matrix4x4[] transforms, Texture)` | Instanced rendering |
| `renderBillboard(Texture, Vec3 pos, float size)` | Camera-facing billboard |
| `renderSkybox(Texture)` | Skybox (via inverse VP matrix) |
| `renderSkybox(Texture, float size)` | Skybox with size factor |
| `renderParticles(ParticleEmitter, Texture)` | Render particle system |
| `renderAnimatedMesh(Mesh, Matrix4x4, Skeleton, AnimationClip, float time, Texture)` | Skeletal animation |
| `renderShadowDepth(Mesh, Matrix4x4 world)` | Shadow map depth pass |
| `drawText(String, int x, int y, Color)` | Screen-space text |
| `getImage()` | Get BufferedImage of current frame |

### Lighting

| Method | Description |
|---|---|
| `setLightDirection(Vec3)` | Directional light |
| `setLighting(float ambient, float diffuse)` | Ambient/diffuse intensity |
| `setSpecular(float intensity, float shininess)` | Blinn-Phong specular |

### Shadows

| Method | Description |
|---|---|
| `enableShadows(int size)` | Enable shadow mapping |
| `setLightMatrix(Vec3 pos, Vec3 target)` | Light POV for shadow map |
| `setShadowBias(float)` | Bias to avoid self-shadowing |
| `setShadowIntensity(float)` | Darkness 0-1 |
| `clearShadowMap()` | Clear shadow map |
| `getShadowMapImage()` | Debug view of shadow map |

### Mesh & Geometry

| Method | Description |
|---|---|
| `new Mesh(Vertex[], int[] indices)` | Create mesh |
| `Mesh.createCube(float size, Color)` | Solid-color cube |
| `Mesh.createTexturedCube(float size, Texture)` | Textured cube |
| `Mesh.loadOBJ(String filename)` | Load OBJ (supports quads/ngons, MTL materials) |
| `Mesh.loadMTL(String filename)` | Load MTL material library |

### Texture

| Method | Description |
|---|---|
| `new Texture(int w, int h)` | Empty texture |
| `Texture.createCheckerboard(w, h, tileSize, Color, Color)` | Procedural checkerboard |
| `Texture.load(String filename)` | Load image file |
| `generateMipmaps()` | Generate mip chain |
| `sample(float u, float v, float lod)` | Mipmapped texture sample |

### Skeletal Animation

| Class | Description |
|---|---|
| `Skeleton(int numBones)` | Bone hierarchy with inverse bind pose |
| `AnimationKeyframe` | Time + translation/rotation/scale |
| `AnimationClip(int numBones, float duration)` | Keyframe tracks per bone |
| `clip.sample(float time, Matrix4x4[] out)` | Sample animation at time |

### Particles

| Class | Description |
|---|---|
| `Particle` | Position, velocity, color, size, life |
| `ParticleEmitter` | Spawns/updates particles with configurable rate, speed, spread, colors |
| `emitter.update(float dt)` | Step simulation |
| `renderParticles(emitter, texture)` | Render (auto-billboarded) |

### Matrix4x4

`identity()`, `translate(x,y,z)`, `rotateX/Y/Z(rad)`, `scale(x,y,z)`, `lookAt(eye,target,up)`, `perspective(fov,aspect,near,far)`, `mul(a,b)`, `clone()`, `invert()`, `getArray()`

### Vec3 / Vec4

`add`, `sub`, `scale`, `dot`, `cross`, `normalize`, `length`, `clone`

## Features

- Software rasterizer (CPU, no GPU)
- Perspective-correct texture & attribute interpolation
- Z-buffer depth testing
- Full 6-plane view frustum clipping (Sutherland-Hodgman)
- Per-vertex Blinn-Phong lighting + per-pixel normal mapping
- Shadow mapping with 3×3 PCF
- Mipmapping with automatic LOD selection
- Gamma correction (sRGB)
- Fog (linear distance)
- Bloom post-process
- FXAA anti-aliasing
- Wireframe / solid wireframe overlay
- Alpha / additive blending
- Billboard rendering
- Particle system
- Skeletal animation (keyframes, skinning)
- Quad/ngon OBJ loading with MTL material support

## Build

```
javac Super3DX.java
jar cf Super3DX.jar Super3DX.class Super3DX$*.class
```

Or run `build.bat`.

## Files

```
Super3DX.java       — complete engine
Super3DXTest.java   — interactive demo (press 0-9, SPACE)
build.bat           — build script
Super3DX.jar        — pre-built library
```

Run the demo: `java -cp Super3DX.jar;. Super3DXTest`
