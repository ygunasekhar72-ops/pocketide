/*
 * PocketIDE native PTY library - P1 packaging stub.
 *
 * M1-P1 establishes only the native build/packaging path: a tiny, documented
 * shared object that AGP compiles per ABI and the APK packages. PTY creation
 * (posix_openpt/forkpty), process execution, session JNI methods and terminal
 * behaviour are later M1 phases; nothing here touches processes or files.
 */
#include <jni.h>

static const char *POCKETPTY_VERSION = "pocketpty 0.1.0-p1";

/*
 * Packaging probe reserved for M1-P2/P4 acceptance: lets an upcoming app-side
 * bridge confirm System.loadLibrary() and the packaged ABI without exercising
 * any PTY/process logic. No Kotlin caller exists yet (intentional in P1).
 */
JNIEXPORT jstring JNICALL
Java_com_pocketide_mobile_terminal_PtyBridge_nativeVersion(JNIEnv *env, jclass clazz)
{
    (void)clazz;
    return (*env)->NewStringUTF(env, POCKETPTY_VERSION);
}
