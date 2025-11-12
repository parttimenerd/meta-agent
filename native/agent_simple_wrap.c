/**
 * A JVMTI agent that wraps the SetEventCallbacks function to intercept
 * the ClassFileLoadHook callback, allowing for custom behavior when classes
 * are loaded.
 */

#include <jvmti.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>

// Original JVMTI function pointer
static jvmtiError (*original_SetEventCallbacks)(jvmtiEnv*, const jvmtiEventCallbacks*, jint) = NULL;

// Original ClassFileLoadHook callback from the instrumentation agent
static void (*original_ClassFileLoadHook)(jvmtiEnv *jvmti, JNIEnv *jni, 
                                          jclass class_being_redefined, jobject loader, 
                                          const char *name, jobject protection_domain, 
                                          jint class_data_len, const unsigned char *class_data, 
                                          jint *new_class_data_len, unsigned char **new_class_data) = NULL;

// Our wrapper for ClassFileLoadHook
static void JNICALL
wrapped_ClassFileLoadHook(jvmtiEnv *jvmti, JNIEnv *jni, jclass class_being_redefined,
                          jobject loader, const char *name, jobject protection_domain,
                          jint class_data_len, const unsigned char *class_data,
                          jint *new_class_data_len, unsigned char **new_class_data) {
    printf("[WRAPPER] ClassFileLoadHook called for class: %s\n", name ? name : "NULL");
    // Call the original ClassFileLoadHook
    original_ClassFileLoadHook(jvmti, jni, class_being_redefined, loader, name,
                                protection_domain, class_data_len, class_data,
                                new_class_data_len, new_class_data);
}

// Our wrapper for SetEventCallbacks
jvmtiError
SetEventCallbacks(jvmtiEnv* env, jvmtiEventCallbacks* callbacks, jint size_of_callbacks) {
    printf("[WRAPPER] SetEventCallbacks called\n");
    
    if (callbacks != NULL && callbacks->ClassFileLoadHook != NULL) {
        printf("[WRAPPER] Intercepting ClassFileLoadHook callback\n");
        
        // Store the original ClassFileLoadHook callback
        original_ClassFileLoadHook = callbacks->ClassFileLoadHook;

        // Replace with our wrapped version
        callbacks->ClassFileLoadHook = wrapped_ClassFileLoadHook;

        // Call original SetEventCallbacks
        return original_SetEventCallbacks(env, callbacks, size_of_callbacks);
    }
    
    // No ClassFileLoadHook to wrap, just pass through
    return original_SetEventCallbacks(env, callbacks, size_of_callbacks);
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    jvmtiEnv *jvmti = NULL;
    jint res;

    printf("[WRAPPER] Agent loading...\n");

    /* Obtain JVMTI environment */
    res = (*vm)->GetEnv(vm, (void**)&jvmti, JVMTI_VERSION_1_2);
    if (res != JNI_OK || jvmti == NULL) {
        printf("[WRAPPER] ERROR: Unable to get JVMTI environment (res=%d)\n", res);
        return JNI_ERR;
    }

    /* Store the original SetEventCallbacks function pointer */
    original_SetEventCallbacks = (*jvmti)->SetEventCallbacks;
    
    if (original_SetEventCallbacks == NULL) {
        printf("[WRAPPER] ERROR: SetEventCallbacks function pointer is NULL\n");
        return JNI_ERR;
    }

    /* Replace SetEventCallbacks with our wrapper */
    *(void**)&((*jvmti)->SetEventCallbacks) = &SetEventCallbacks;
    
    printf("[WRAPPER] Successfully wrapped SetEventCallbacks\n");
    
    return JNI_OK;
}