public class Light {
    public enum Type { DIRECTIONAL, POINT, AMBIENT, SPOT }

    public Type type = Type.DIRECTIONAL;
    public float r = 1f, g = 1f, b = 1f;
    public float intensity = 1f;
    public Super3DX.Vec3 position = new Super3DX.Vec3();
    public Super3DX.Vec3 direction = new Super3DX.Vec3(0, -1, 0);
    public float range = 10f;
    public float spotInnerAngle = 15f;
    public float spotOuterAngle = 30f;
    public float constantAtten = 1f, linearAtten = 0.09f, quadraticAtten = 0.032f;
    public boolean castShadows = false;

    public static Light directional(Super3DX.Vec3 dir, float r, float g, float b, float intensity) {
        Light l = new Light();
        l.type = Type.DIRECTIONAL;
        l.direction = dir.normalize();
        l.r = r; l.g = g; l.b = b;
        l.intensity = intensity;
        return l;
    }

    public static Light directional(Super3DX.Vec3 dir, Super3DX.Vec3 color, float intensity) {
        return directional(dir, color.x, color.y, color.z, intensity);
    }

    public static Light point(Super3DX.Vec3 pos, float r, float g, float b, float intensity, float range) {
        Light l = new Light();
        l.type = Type.POINT;
        l.position = pos;
        l.r = r; l.g = g; l.b = b;
        l.intensity = intensity;
        l.range = range;
        return l;
    }

    public static Light point(Super3DX.Vec3 pos, Super3DX.Vec3 color, float intensity, float range) {
        return point(pos, color.x, color.y, color.z, intensity, range);
    }

    public static Light ambient(float r, float g, float b, float intensity) {
        Light l = new Light();
        l.type = Type.AMBIENT;
        l.r = r; l.g = g; l.b = b;
        l.intensity = intensity;
        return l;
    }

    public static Light ambient(Super3DX.Vec3 color, float intensity) {
        return ambient(color.x, color.y, color.z, intensity);
    }

    public static Light spot(Super3DX.Vec3 pos, Super3DX.Vec3 dir, float r, float g, float b, float intensity, float range, float innerAngleDeg, float outerAngleDeg) {
        Light l = new Light();
        l.type = Type.SPOT;
        l.position = pos;
        l.direction = dir.normalize();
        l.r = r; l.g = g; l.b = b;
        l.intensity = intensity;
        l.range = range;
        l.spotInnerAngle = innerAngleDeg;
        l.spotOuterAngle = outerAngleDeg;
        return l;
    }

    public static Light spot(Super3DX.Vec3 pos, Super3DX.Vec3 dir, Super3DX.Vec3 color, float intensity, float range, float innerAngleDeg, float outerAngleDeg) {
        return spot(pos, dir, color.x, color.y, color.z, intensity, range, innerAngleDeg, outerAngleDeg);
    }
}
