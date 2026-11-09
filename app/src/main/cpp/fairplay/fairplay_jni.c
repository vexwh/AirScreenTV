#include <jni.h>
#include <stddef.h>
#include <string.h>
#include "playfair.h"

JNIEXPORT jbyteArray JNICALL
Java_com_airscreen_tv_protocols_airplay_crypto_FairPlayNative_decrypt(
    JNIEnv *env, jobject thiz, jbyteArray keyMessage, jbyteArray ekey) {
    (void) thiz;
    if (keyMessage == NULL || ekey == NULL) return NULL;
    jsize kmLen = (*env)->GetArrayLength(env, keyMessage);
    jsize ekLen = (*env)->GetArrayLength(env, ekey);
    if (kmLen < 164 || ekLen < 72) return NULL;

    unsigned char km[164];
    unsigned char ek[72];
    unsigned char out[16];
    (*env)->GetByteArrayRegion(env, keyMessage, 0, 164, (jbyte *) km);
    (*env)->GetByteArrayRegion(env, ekey, 0, 72, (jbyte *) ek);
    playfair_decrypt(km, ek, out);

    jbyteArray result = (*env)->NewByteArray(env, 16);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, 16, (jbyte *) out);
    }
    return result;
}