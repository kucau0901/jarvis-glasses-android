// The LC3 decoder for app.jarvis.glasses.audio.Lc3Decoder: one decoder per instance, mono, 16-bit.
#include <jni.h>
#include <stdlib.h>
#include "lc3.h"

typedef struct {
  lc3_decoder_t decoder;
  int samples;
} Decoder;

JNIEXPORT jlong JNICALL
Java_app_jarvis_glasses_audio_Lc3Decoder_open(JNIEnv *env, jclass cls, jint frameUs, jint rate) {
  unsigned size = lc3_decoder_size(frameUs, rate);
  if (size == 0) return 0;
  Decoder *d = malloc(sizeof(Decoder) + size);
  if (!d) return 0;
  d->decoder = lc3_setup_decoder(frameUs, rate, 0, (char *)d + sizeof(Decoder));
  d->samples = lc3_frame_samples(frameUs, rate);
  if (!d->decoder) {
    free(d);
    return 0;
  }
  return (jlong)(intptr_t)d;
}

// Decodes [length] bytes of [frame] from [offset] into [pcm], which holds one frame's samples.
// 0 when decoded, 1 when the frame was bad and concealed, -1 on a wrong argument.
JNIEXPORT jint JNICALL
Java_app_jarvis_glasses_audio_Lc3Decoder_decode(JNIEnv *env, jclass cls, jlong handle, jbyteArray frame, jint offset,
                                                jint length, jshortArray pcm) {
  Decoder *d = (Decoder *)(intptr_t)handle;
  if (!d || (*env)->GetArrayLength(env, pcm) < d->samples) return -1;
  if (offset < 0 || length <= 0 || offset + length > (*env)->GetArrayLength(env, frame)) return -1;
  jbyte *in = (*env)->GetByteArrayElements(env, frame, NULL);
  jshort *out = (*env)->GetShortArrayElements(env, pcm, NULL);
  int r = lc3_decode(d->decoder, in + offset, length, LC3_PCM_FORMAT_S16, out, 1);
  (*env)->ReleaseShortArrayElements(env, pcm, out, 0);
  (*env)->ReleaseByteArrayElements(env, frame, in, JNI_ABORT);
  return r;
}

JNIEXPORT void JNICALL
Java_app_jarvis_glasses_audio_Lc3Decoder_close(JNIEnv *env, jclass cls, jlong handle) {
  free((Decoder *)(intptr_t)handle);
}
