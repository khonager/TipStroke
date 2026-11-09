#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tipstroke_drawing_android_NativeEraser_applySegment(
    JNIEnv* env, jobject, jintArray pixelArray, jintArray originalArray,
    jbyteArray alphaArray, jfloatArray distanceArray,
    jint stride, jint x0, jint x1, jint y0, jint y1,
    jfloat ax, jfloat ay, jfloat dx, jfloat dy, jfloat inverseLengthSquared,
    jfloat sizeA, jfloat sizeB, jfloat alphaA, jfloat alphaB, jfloat hardness) {
    // These gesture-owned arrays are fixed at one 256px tile. Pin only for this
    // bounded rectangle; no allocation, callbacks, I/O or blocking while pinned.
    auto* pixels = static_cast<jint*>(env->GetPrimitiveArrayCritical(pixelArray, nullptr));
    if (!pixels) return JNI_FALSE;
    auto* originals = static_cast<jint*>(env->GetPrimitiveArrayCritical(originalArray, nullptr));
    if (!originals) { env->ReleasePrimitiveArrayCritical(pixelArray, pixels, 0); return JNI_FALSE; }
    auto* alpha = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(alphaArray, nullptr));
    if (!alpha) {
        env->ReleasePrimitiveArrayCritical(originalArray, originals, JNI_ABORT);
        env->ReleasePrimitiveArrayCritical(pixelArray, pixels, 0); return JNI_FALSE;
    }
    auto* distances = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(distanceArray, nullptr));
    if (!distances) {
        env->ReleasePrimitiveArrayCritical(alphaArray, alpha, 0);
        env->ReleasePrimitiveArrayCritical(originalArray, originals, JNI_ABORT);
        env->ReleasePrimitiveArrayCritical(pixelArray, pixels, 0); return JNI_FALSE;
    }
    bool changed = false;
    const float softness = 1.0f - hardness;
    for (int y = y0; y < y1; ++y) for (int x = x0; x < x1; ++x) {
        const int i = y * stride + x;
        const uint32_t original = static_cast<uint32_t>(originals[i]);
        if ((original >> 24) == 0) continue;
        const float px = x + .5f - ax;
        const float py = y + .5f - ay;
        const float t = inverseLengthSquared > 0 ? std::clamp((px * dx + py * dy) * inverseLengthSquared, 0.0f, 1.0f) : 1.0f;
        const float ex = px - t * dx, ey = py - t * dy;
        const float distance = ex * ex + ey * ey;
        const float previous = distances[i];
        const bool nearer = distance < previous - .0001f;
        const bool tied = std::abs(distance - previous) <= .0001f;
        if (!nearer && !tied) continue;
        const float size = sizeA + (sizeB - sizeA) * t;
        const float outer = size * (.5f + .75f * softness) + .5f;
        const float inner = std::max(0.0f, size * .5f * hardness - .5f);
        const int old = static_cast<uint8_t>(alpha[i]);
        int coverage = 0;
        if (distance < outer * outer) {
            float falloff = 1.0f;
            if (distance > inner * inner) {
                const float u = std::clamp((outer - std::sqrt(distance)) / (outer - inner), 0.0f, 1.0f);
                falloff = u * u * (3.0f - 2.0f * u);
            }
            coverage = std::clamp(static_cast<int>(std::floor(static_cast<double>((alphaA + (alphaB - alphaA) * t) * falloff) + .5)), 0, 255);
        }
        const int combined = nearer ? coverage : std::max(old, coverage);
        if (nearer) distances[i] = distance;
        if (combined == old) continue;
        alpha[i] = static_cast<jbyte>(combined);
        changed = true;
        const uint32_t remaining = ((original >> 24) * (255 - combined) + 127) / 255;
        pixels[i] = static_cast<jint>(remaining == 0 ? 0 : (original & 0x00ffffff) | (remaining << 24));
    }
    env->ReleasePrimitiveArrayCritical(distanceArray, distances, 0);
    env->ReleasePrimitiveArrayCritical(alphaArray, alpha, 0);
    env->ReleasePrimitiveArrayCritical(originalArray, originals, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(pixelArray, pixels, 0);
    return changed ? JNI_TRUE : JNI_FALSE;
}
