/**
 * A minimal JVMTI agent in C that registers a ClassFileLoadHook callback.
 * This agent prints the names of classes as they are loaded by the JVM.
 */

#include <jvmti.h>
#include <stdio.h>
#include <string.h>

// The callback: called whenever a class is loaded by the JVM
void JNICALL ClassFileLoadHook(
    jvmtiEnv *jvmti,
    JNIEnv *jni,
    jclass class_being_redefined,
    jobject loader,
    const char *name,
    jobject protection_domain,
    jint class_data_len,
    const unsigned char *class_data,
    jint *new_class_data_len,
    unsigned char **new_class_data
) {
   if (name) {
        printf("[Agent] Class loaded: %s\n", name);
    } else {
        printf("[Agent] Anonymous class loaded.\n");
    }
}

// Called when the agent is first loaded
JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    jvmtiEnv *jvmti;
    jvmtiError err;

    printf("[Agent] Agent_OnLoad called.\n");

    // Get the JVMTI environment
    if ((*vm)->GetEnv(vm, (void **)&jvmti, JVMTI_VERSION_1_2) != JNI_OK) {
        printf("[Agent] Unable to get JVMTI environment.\n");
        return JNI_ERR;
    }

    // Set capabilities (request access to class load hooks)
    jvmtiCapabilities caps;
    memset(&caps, 0, sizeof(caps));
    caps.can_generate_all_class_hook_events = 1;
    err = (*jvmti)->AddCapabilities(jvmti, &caps);
    if (err != JVMTI_ERROR_NONE) {
        printf("[Agent] AddCapabilities failed: %d\n", err);
        return JNI_ERR;
    }

    // Register the callback
    jvmtiEventCallbacks callbacks;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.ClassFileLoadHook = &ClassFileLoadHook;

    err = (*jvmti)->SetEventCallbacks(jvmti, &callbacks, sizeof(callbacks));
    if (err != JVMTI_ERROR_NONE) {
        printf("[Agent] SetEventCallbacks failed: %d\n", err);
        return JNI_ERR;
    }

    // Enable the ClassFileLoadHook event
    err = (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, NULL);
    if (err != JVMTI_ERROR_NONE) {
        printf("[Agent] SetEventNotificationMode failed: %d\n", err);
        return JNI_ERR;
    }

    printf("[Agent] ClassFileLoadHook registered.\n");
    return JNI_OK;
}

// Optional: cleanup
JNIEXPORT void JNICALL Agent_OnUnload(JavaVM *vm) {
    printf("[Agent] Agent_OnUnload called.\n");
}