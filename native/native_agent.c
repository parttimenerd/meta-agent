/*
 * Multi-Agent ClassFileLoadHook Coordinator
 * 
 * Coordinates multiple JVMTI agents using ClassFileLoadHook callbacks.
 * Load this agent FIRST before other ClassFileLoadHook agents.
 *
 * Usage: java -agentpath:<path>=log=<silent,normal,verbose>,always=<true|false>,skip=<agent_name> [other agents...] YourClass
 *        Use -agentpath:<path>=help for detailed help
 * 
 * Example: java -agentpath:./libagent.dylib=log=verbose,skip=instrument \
 *               -agentpath:./libagent_minimal_cfh.dylib HelloWorld
 *
 * File-based Communication:
 * - Creates /tmp/njvm<pid>/ directory for communication with meta-agent
 * - Each transformation creates /tmp/njvm<pid>/<counter> file with diff data atomically
 *
 * File Format for /tmp/njvm<pid>/<counter>:
 * - Line 1: agent_name (e.g., "agent_minimal_cfh")
 * - Line 2: class_name (e.g., "java/lang/String" or "unknown")
 * - Line 3: old_len (decimal number, e.g., "1234")
 * - Line 4: new_len (decimal number, e.g., "1456")
 * - Binary data: old_len bytes of original class data
 * - Binary data: new_len bytes of transformed class data
 */

#define _GNU_SOURCE

#include <jvmti.h>
#include <jni.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <dirent.h>
#include <errno.h>

/* Configuration constants */
#define MAX_AGENTS 4096                  /* Maximum number of agents (sized for hex indexing: 0x000-0xFFF) */
#define MAX_AGENT_NAME_LEN 256           /* Maximum length of agent name */
#define MAX_PATH_LEN 1024                /* Maximum path length for file operations */
#define COMM_DIR_PERMISSIONS 0755        /* Directory permissions for communication directory */

/* Agent information structure - simplified, just stores original callback and name */
typedef struct {
    void (*callback)(jvmtiEnv *jvmti, JNIEnv *jni, jclass class_being_redefined, 
                     jobject loader, const char *name, jobject protection_domain, 
                     jint class_data_len, const unsigned char *class_data, 
                     jint *new_class_data_len, unsigned char **new_class_data);
    char name[MAX_AGENT_NAME_LEN];
} ClassFileLoadHookInfo;

// JVMTI function pointer types
typedef jvmtiError (*SetEventCallbacksFunc)(jvmtiEnv* env, const jvmtiEventCallbacks* callbacks, jint size_of_callbacks);

// Original JVMTI function pointers
static SetEventCallbacksFunc original_SetEventCallbacks = NULL;

// Flag to detect if agent is already loaded
static volatile int agent_already_loaded = 0;

// Agent tracking

// Logging configuration
static int log_level = 1; // 0=silent, 1=normal, 2=verbose
static int always_file = 0; // If 1, always generate diff files even when no transformation

// Skip list configuration
#define MAX_SKIP_AGENTS 64
static char skip_agents[MAX_SKIP_AGENTS][MAX_AGENT_NAME_LEN];
static int skip_agents_count = 0;

// Helper macros for configurable logging
#define LOG_NORMAL(fmt, ...) do { if (log_level >= 1) printf(fmt, ##__VA_ARGS__); } while(0)
#define LOG_VERBOSE(fmt, ...) do { if (log_level >= 2) printf(fmt, ##__VA_ARGS__); } while(0)
#define LOG_ERROR(fmt, ...) do { fprintf(stderr, "[NATIVE_AGENT] ERROR: " fmt, ##__VA_ARGS__); } while(0)

// File-based communication
static char comm_dir[MAX_PATH_LEN];
static char temp_dir[MAX_PATH_LEN];
static volatile int file_counter = 0;  /* Thread-safe counter using atomic operations */

// Array to store original agent callbacks and their info
static ClassFileLoadHookInfo agent_info[MAX_AGENTS];
static int next_agent_slot = 0;

// Array of wrapper function pointers (will be populated by macro-generated functions)
typedef void (*WrapperFunc)(jvmtiEnv*, JNIEnv*, jclass, jobject, const char*, jobject, 
                            jint, const unsigned char*, jint*, unsigned char**);
static WrapperFunc wrapper_functions[MAX_AGENTS];

// Forward declarations
static void write_transformation_to_file(const char* agent_name, const char* class_name, 
                                         const unsigned char* old_data, jint old_len,
                                         const unsigned char* new_data, jint new_len);
static int is_agent_skipped(const char* agent_name);
static void remove_directory(const char* path);
static int setup_directories(pid_t pid);
static void log_configuration(void);
static void cleanup_directories(void);
static void extract_agent_name(char* dest, size_t dest_size, const char* library_path);

// Helper function to check if an agent should be skipped
static int is_agent_skipped(const char* agent_name) {
    if (agent_name == NULL) return 0;
    
    for (int i = 0; i < skip_agents_count; i++) {
        if (strcmp(skip_agents[i], agent_name) == 0) {
            LOG_VERBOSE("[NATIVE_AGENT] Skipping agent: %s\n", agent_name);
            return 1;
        }
    }
    return 0;
}

// Common wrapper handler that all generated wrappers call
static void wrapper_handler(int agent_index,
                           jvmtiEnv *jvmti, JNIEnv *jni, jclass class_being_redefined,
                           jobject loader, const char *name, jobject protection_domain,
                           jint class_data_len, const unsigned char *class_data,
                           jint *new_class_data_len, unsigned char **new_class_data) {
    if (agent_index < 0 || agent_index >= next_agent_slot) {
        LOG_ERROR("Invalid agent index: %d\n", agent_index);
        return;
    }

    ClassFileLoadHookInfo* info = &agent_info[agent_index];
    
    // Check if this agent should be skipped
    if (is_agent_skipped(info->name)) {
        LOG_VERBOSE("[NATIVE_AGENT] Skipping Java meta-agent call for agent: %s\n", info->name);
        return;
    }
    
    const unsigned char* old_data = class_data;
    jint old_len = class_data_len;
    
    // Call the original agent's callback
    if (info->callback != NULL) {
        info->callback(jvmti, jni, class_being_redefined, loader, name,
                      protection_domain, class_data_len, class_data,
                      new_class_data_len, new_class_data);
        
        // Write diff if transformation occurred or always_file is set
        if (new_class_data != NULL && *new_class_data != NULL && 
            new_class_data_len != NULL && *new_class_data_len > 0 &&
            (*new_class_data_len != old_len || memcmp(old_data, *new_class_data, old_len) != 0)) {
            printf("[NATIVE_AGENT] Agent %s transformed class %s (old_len=%d, new_len=%d)\n",
                   info->name, name ? name : "NULL", old_len, *new_class_data_len);
            if (0) {
                LOG_VERBOSE("[NATIVE_AGENT] No actual changes detected for class %s by agent %s\n",
                            name ? name : "NULL", info->name);
            }
            write_transformation_to_file(info->name, name, old_data, old_len, 
                                        *new_class_data, *new_class_data_len);
        } else if (always_file) {
            write_transformation_to_file(info->name, name, old_data, old_len, old_data, old_len);
        }
    }
}

// Macro to generate a single wrapper function at a specific index
#define WRAPPER_FUNC(index) \
    static void JNICALL wrapper_##index( \
        jvmtiEnv *jvmti, JNIEnv *jni, jclass class_being_redefined, \
        jobject loader, const char *name, jobject protection_domain, \
        jint class_data_len, const unsigned char *class_data, \
        jint *new_class_data_len, unsigned char **new_class_data) { \
        wrapper_handler((0x##index), jvmti, jni, class_being_redefined, loader, name, \
                       protection_domain, class_data_len, class_data, \
                       new_class_data_len, new_class_data); \
    }

// Macro to generate 16 wrapper functions at once (0-F in hex)
#define WRAPPER_FUNC_16(base) \
    WRAPPER_FUNC(base##0) WRAPPER_FUNC(base##1) WRAPPER_FUNC(base##2) WRAPPER_FUNC(base##3) \
    WRAPPER_FUNC(base##4) WRAPPER_FUNC(base##5) WRAPPER_FUNC(base##6) WRAPPER_FUNC(base##7) \
    WRAPPER_FUNC(base##8) WRAPPER_FUNC(base##9) WRAPPER_FUNC(base##A) WRAPPER_FUNC(base##B) \
    WRAPPER_FUNC(base##C) WRAPPER_FUNC(base##D) WRAPPER_FUNC(base##E) WRAPPER_FUNC(base##F)

// Macro to generate 256 wrapper functions at once (16x16 = 256)
#define WRAPPER_FUNC_256(base) \
    WRAPPER_FUNC_16(base##0) WRAPPER_FUNC_16(base##1) WRAPPER_FUNC_16(base##2) WRAPPER_FUNC_16(base##3) \
    WRAPPER_FUNC_16(base##4) WRAPPER_FUNC_16(base##5) WRAPPER_FUNC_16(base##6) WRAPPER_FUNC_16(base##7) \
    WRAPPER_FUNC_16(base##8) WRAPPER_FUNC_16(base##9) WRAPPER_FUNC_16(base##A) WRAPPER_FUNC_16(base##B) \
    WRAPPER_FUNC_16(base##C) WRAPPER_FUNC_16(base##D) WRAPPER_FUNC_16(base##E) WRAPPER_FUNC_16(base##F)

// Generate 4096 wrapper functions (wrapper_000 through wrapper_FFF) - hex 0x000-0xFFF
WRAPPER_FUNC_256(0)  WRAPPER_FUNC_256(1)  WRAPPER_FUNC_256(2)  WRAPPER_FUNC_256(3)
WRAPPER_FUNC_256(4)  WRAPPER_FUNC_256(5)  WRAPPER_FUNC_256(6)  WRAPPER_FUNC_256(7)
WRAPPER_FUNC_256(8)  WRAPPER_FUNC_256(9)  WRAPPER_FUNC_256(A)  WRAPPER_FUNC_256(B)
WRAPPER_FUNC_256(C)  WRAPPER_FUNC_256(D)  WRAPPER_FUNC_256(E)  WRAPPER_FUNC_256(F)

// Macro to initialize wrapper function pointer array entry
#define INIT_WRAPPER(index) wrapper_functions[0x##index] = &wrapper_##index

// Macro to initialize 16 wrapper function pointers at once (0-F in hex)
#define INIT_WRAPPER_16(base) do { \
    INIT_WRAPPER(base##0); INIT_WRAPPER(base##1); INIT_WRAPPER(base##2); INIT_WRAPPER(base##3); \
    INIT_WRAPPER(base##4); INIT_WRAPPER(base##5); INIT_WRAPPER(base##6); INIT_WRAPPER(base##7); \
    INIT_WRAPPER(base##8); INIT_WRAPPER(base##9); INIT_WRAPPER(base##A); INIT_WRAPPER(base##B); \
    INIT_WRAPPER(base##C); INIT_WRAPPER(base##D); INIT_WRAPPER(base##E); INIT_WRAPPER(base##F); \
} while(0)

// Macro to initialize 256 wrapper function pointers at once (16x16 = 256)
#define INIT_WRAPPER_256(base) do { \
    INIT_WRAPPER_16(base##0); INIT_WRAPPER_16(base##1); INIT_WRAPPER_16(base##2); INIT_WRAPPER_16(base##3); \
    INIT_WRAPPER_16(base##4); INIT_WRAPPER_16(base##5); INIT_WRAPPER_16(base##6); INIT_WRAPPER_16(base##7); \
    INIT_WRAPPER_16(base##8); INIT_WRAPPER_16(base##9); INIT_WRAPPER_16(base##A); INIT_WRAPPER_16(base##B); \
    INIT_WRAPPER_16(base##C); INIT_WRAPPER_16(base##D); INIT_WRAPPER_16(base##E); INIT_WRAPPER_16(base##F); \
} while(0)

// Function to initialize wrapper function pointers (hex 0x000-0xFFF)
static void initialize_wrapper_functions(void) {
    INIT_WRAPPER_256(0);  INIT_WRAPPER_256(1);  INIT_WRAPPER_256(2);  INIT_WRAPPER_256(3);
    INIT_WRAPPER_256(4);  INIT_WRAPPER_256(5);  INIT_WRAPPER_256(6);  INIT_WRAPPER_256(7);
    INIT_WRAPPER_256(8);  INIT_WRAPPER_256(9);  INIT_WRAPPER_256(A);  INIT_WRAPPER_256(B);
    INIT_WRAPPER_256(C);  INIT_WRAPPER_256(D);  INIT_WRAPPER_256(E);  INIT_WRAPPER_256(F);
}

// Parse agent options (passed as -agentpath:<path>=<options>)
// Returns 0 on success, 1 if help was requested, -1 on error
static int parse_agent_options(const char* options) {
    // Build combined options string: env_args + "," + agent_args
    char combined_options[MAX_PATH_LEN * 2];
    combined_options[0] = '\0';
    
    // Check for NATIVE_WRAPPER_ARGS environment variable
    const char* env_args = getenv("NATIVE_WRAPPER_ARGS");
    if (env_args != NULL && strlen(env_args) > 0) {
        strncpy(combined_options, env_args, sizeof(combined_options) - 1);
        combined_options[sizeof(combined_options) - 1] = '\0';
    }
    
    // Append agent options if provided
    if (options != NULL && strlen(options) > 0) {
        size_t current_len = strlen(combined_options);
        if (current_len > 0) {
            // Add comma separator
            if (current_len < sizeof(combined_options) - 1) {
                strncat(combined_options, ",", sizeof(combined_options) - current_len - 1);
                current_len++;
            }
        }
        if (current_len < sizeof(combined_options) - 1) {
            strncat(combined_options, options, sizeof(combined_options) - current_len - 1);
        }
    }
    
    // If no options at all, return success
    if (strlen(combined_options) == 0) {
        return 0;
    }
    
    // Make a copy of combined options string since strtok modifies it
    char* options_copy = strdup(combined_options);
    if (options_copy == NULL) {
        LOG_ERROR("Failed to allocate memory for options string\n");
        return -1;
    }
    
    char* token = strtok(options_copy, ",");
    while (token != NULL) {
        // Trim leading whitespace
        while (*token == ' ' || *token == '\t') token++;
        
        // Check for help
        if (strcmp(token, "help") == 0) {
            free(options_copy);
            return 1;  // Help requested
        }
        
        // Parse key=value pairs
        char* equals = strchr(token, '=');
        if (equals != NULL) {
            *equals = '\0';
            char* key = token;
            char* value = equals + 1;
            
            if (strcmp(key, "log") == 0) {
                if (strcmp(value, "silent") == 0 || strcmp(value, "0") == 0) {
                    log_level = 0;
                } else if (strcmp(value, "normal") == 0 || strcmp(value, "1") == 0) {
                    log_level = 1;
                } else if (strcmp(value, "verbose") == 0 || strcmp(value, "2") == 0) {
                    log_level = 2;
                } else {
                    LOG_ERROR("Invalid log level: %s (use silent/0, normal/1, or verbose/2)\n", value);
                }
            } else if (strcmp(key, "always") == 0) {
                if (strcmp(value, "true") == 0 || strcmp(value, "1") == 0) {
                    always_file = 1;
                } else if (strcmp(value, "false") == 0 || strcmp(value, "0") == 0) {
                    always_file = 0;
                } else {
                    LOG_ERROR("Invalid always value: %s (use true/1 or false/0)\n", value);
                }
            } else if (strcmp(key, "skip") == 0) {
                // Add single agent to skip list
                if (skip_agents_count < MAX_SKIP_AGENTS) {
                    strncpy(skip_agents[skip_agents_count], value, MAX_AGENT_NAME_LEN - 1);
                    skip_agents[skip_agents_count][MAX_AGENT_NAME_LEN - 1] = '\0';
                    skip_agents_count++;
                } else {
                    LOG_ERROR("Maximum number of skip agents (%d) reached\n", MAX_SKIP_AGENTS);
                }
            } else {
                LOG_ERROR("Unknown option: %s\n", key);
            }
        } else {
            LOG_ERROR("Invalid option format (expected key=value): %s\n", token);
        }
        
        token = strtok(NULL, ",");
    }
    
    free(options_copy);
    return 0;
}

// Clean up all directories (comm_dir and temp_dir)
static void cleanup_directories(void) {
    remove_directory(temp_dir);
    LOG_VERBOSE("[NATIVE_AGENT] Removed temp directory: %s\n", temp_dir);
    remove_directory(comm_dir);
    LOG_VERBOSE("[NATIVE_AGENT] Removed communication directory: %s\n", comm_dir);
}

// Setup communication directories
// Returns 0 on success, -1 on error
static int setup_directories(pid_t pid) {
    // Setup communication directory /tmp/njvm<pid>
    size_t dir_path_len = snprintf(comm_dir, sizeof(comm_dir), "/tmp/njvm%d", pid);
    
    if (dir_path_len >= sizeof(comm_dir)) {
        fprintf(stderr, "ERROR: Communication directory path too long for PID %d\n", pid);
        return -1;
    }
    
    // Setup temp directory /tmp/njvm<pid>_tmp
    size_t temp_dir_path_len = snprintf(temp_dir, sizeof(temp_dir), "/tmp/njvm%d_tmp", pid);
    
    if (temp_dir_path_len >= sizeof(temp_dir)) {
        fprintf(stderr, "ERROR: Temp directory path too long for PID %d\n", pid);
        return -1;
    }
    
    // Remove directories if they already exist
    remove_directory(comm_dir);
    remove_directory(temp_dir);
    
    // Create fresh directories
    if (mkdir(comm_dir, COMM_DIR_PERMISSIONS) != 0) {
        fprintf(stderr, "ERROR: Failed to create communication directory %s: %s\n", 
                comm_dir, strerror(errno));
        return -1;
    }
    
    if (mkdir(temp_dir, COMM_DIR_PERMISSIONS) != 0) {
        fprintf(stderr, "ERROR: Failed to create temp directory %s: %s\n", 
                temp_dir, strerror(errno));
        remove_directory(comm_dir);  // Clean up comm_dir on failure
        return -1;
    }
    
    return 0;
}

// Log the current configuration
static void log_configuration(void) {
    LOG_VERBOSE("[NATIVE_AGENT] Loading native-agent (log_level=%d, always=%d, skip_count=%d, comm_dir=%s)...\n",
               log_level, always_file, skip_agents_count, comm_dir);
    
    // Log skip list if any
    if (skip_agents_count > 0 && log_level >= 2) {
        LOG_VERBOSE("[NATIVE_AGENT] Agents to skip: ");
        for (int i = 0; i < skip_agents_count; i++) {
            printf("%s%s", skip_agents[i], (i < skip_agents_count - 1) ? ", " : "");
        }
        printf("\n");
    }
}

// Extract agent name from library path
// Strips directory path, "lib" prefix, and file extension
static void extract_agent_name(char* dest, size_t dest_size, const char* library_path) {
    if (dest == NULL || dest_size == 0 || library_path == NULL) {
        return;
    }
    
    // Get filename from full path
    const char* filename = strrchr(library_path, '/');
    filename = filename ? filename + 1 : library_path;
    
    // Skip "lib" prefix if present
    const char* name_start = (strncmp(filename, "lib", 3) == 0) ? filename + 3 : filename;
    
    // Copy name with bounds checking
    strncpy(dest, name_start, dest_size - 1);
    dest[dest_size - 1] = '\0';
    
    // Remove file extension
    char* ext = strrchr(dest, '.');
    if (ext) *ext = '\0';
    
    LOG_VERBOSE("[NATIVE_AGENT] Extracted agent name '%s' from library '%s'\n",
                dest, library_path);
}

static void display_help(void) {
    printf("\n");
    printf("==============================================================================\n");
    printf("  Native Agent - Help\n");
    printf("==============================================================================\n");
    printf("\n");
    printf("DESCRIPTION:\n");
    printf("  Wraps multiple JVMTI agents using ClassFileLoadHook callbacks.\n");
    printf("  This agent must be loaded FIRST before other ClassFileLoadHook agents.\n");
    printf("\n");
    printf("USAGE:\n");
    printf("  java -agentpath:<path>=<options> [other agents...] YourClass\n");
    printf("\n");
    printf("AGENT OPTIONS (comma-separated):\n");
    printf("  help\n");
    printf("      Display this help message and exit.\n");
    printf("\n");
    printf("  log=<level>\n");
    printf("      Set logging verbosity.\n");
    printf("      Values: silent (no logging)\n");
    printf("              normal (normal logging, default)\n");
    printf("              verbose (detailed debug information)\n");
    printf("      Example: -agentpath:libnative_agent.dylib=log=verbose\n");
    printf("\n");
    printf("  always=<value>\n");
    printf("      Always generate diff files even when no transformation occurs.\n");
    printf("      Values: true (always generate)\n");
    printf("              false (only when transformed, default)\n");
    printf("      Example: -agentpath:libnative_agent.dylib=always=true\n");
    printf("\n");
    printf("  skip=<agent>\n");
    printf("      Skip wrapping the specified instrumentation agent.\n");
    printf("      Can be specified multiple times to skip multiple agents.\n");
    printf("      Example: -agentpath:libnative_agent.dylib=skip=instrument\n");
    printf("      to skip wrapping libinstrument (the native agent handling Java agents)\n");
    printf("\n");
    printf("ENVIRONMENT VARIABLES:\n");
    printf("  NATIVE_WRAPPER_ARGS\n");
    printf("      Arguments prepended to agent options (same format as agent options).\n");
    printf("      Agent options will override environment variable settings.\n");
    printf("      Example: export NATIVE_WRAPPER_ARGS=\"log=verbose,always=1\"\n");
    printf("\n");
    printf("EXAMPLES:\n");
    printf("  # Display help\n");
    printf("  java -agentpath:./libnative_agent.dylib=help\n");
    printf("\n");
    printf("  # Verbose logging with always generate files\n");
    printf("  java -agentpath:./libnative_agent.dylib=log=verbose,always=1 \\\n");
    printf("       -agentpath:./libagent_minimal_cfh.dylib HelloWorld\n");
    printf("\n");
    printf("FILE-BASED COMMUNICATION:\n");
    printf("  Creates /tmp/njvm<pid>/ directory for communication with the meta-agent.\n");
    printf("  Each transformation creates a numbered file with diff data.\n");
    printf("\n");
    printf("==============================================================================\n");
    printf("\n");
}

// Helper to recursively remove directory and its contents
static void remove_directory(const char* path) {
    if (path == NULL || strlen(path) == 0) {
        return;
    }
    
    DIR* dir = opendir(path);
    if (dir == NULL) {
        return;
    }
    
    struct dirent* entry;
    while ((entry = readdir(dir)) != NULL) {
        // Skip . and .. entries
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
            continue;
        }
        
        char full_path[MAX_PATH_LEN];
        size_t path_len = snprintf(full_path, sizeof(full_path), "%s/%s", path, entry->d_name);
        
        // Check for path truncation
        if (path_len >= sizeof(full_path)) {
            LOG_ERROR("Path too long: %s/%s\n", path, entry->d_name);
            continue;
        }
        
        struct stat st;
        if (stat(full_path, &st) == 0) {
            if (S_ISDIR(st.st_mode)) {
                remove_directory(full_path);
            } else {
                if (unlink(full_path) != 0) {
                    LOG_VERBOSE("[NATIVE_AGENT] Failed to delete file %s: %s\n", full_path, strerror(errno));
                }
            }
        }
    }
    
    closedir(dir);
    if (rmdir(path) != 0) {
        LOG_VERBOSE("[NATIVE_AGENT] Failed to remove directory %s: %s\n", path, strerror(errno));
    }
}

// Helper to write transformation data to file system for Java meta-agent to pick up
// Thread-safe: uses atomic counter and atomic rename operation
static void write_transformation_to_file(const char* agent_name, const char* class_name, 
                                         const unsigned char* old_data, jint old_len,
                                         const unsigned char* new_data, jint new_len) {
    char filepath[MAX_PATH_LEN];
    char temp_filepath[MAX_PATH_LEN];
    int current_counter = __sync_fetch_and_add(&file_counter, 1);
    
    // Build file paths - temp file goes in separate temp directory
    int written = snprintf(filepath, sizeof(filepath), "%s/%d", comm_dir, current_counter);
    if (written < 0 || written >= (int)sizeof(filepath)) {
        LOG_ERROR("Failed to format filepath (truncated)\n");
        return;
    }
    written = snprintf(temp_filepath, sizeof(temp_filepath), "%s/%d", temp_dir, current_counter);
    if (written < 0 || written >= (int)sizeof(temp_filepath)) {
        LOG_ERROR("Failed to format temp_filepath (truncated)\n");
        return;
    }

    LOG_VERBOSE("[NATIVE_AGENT] Writing transformation to temp file: %s (agent=%s, class=%s)\n",
                temp_filepath, agent_name ? agent_name : "unknown", class_name ? class_name : "NULL");
    
    FILE* f = fopen(temp_filepath, "wb");
    if (f == NULL) {
        LOG_ERROR("Failed to open temp file %s: %s\n", temp_filepath, strerror(errno));
        return;
    }
    
    // Write header: agent_name, class_name, old_len, new_len (each on separate line)
    fprintf(f, "%s\n%s\n%d\n%d\n", 
            agent_name ? agent_name : "unknown",
            class_name ? class_name : "unknown", 
            old_len, new_len);
    
    // Write old and new class data
    if (old_len > 0 && old_data != NULL) {
        fwrite(old_data, 1, old_len, f);
    }
    if (new_len > 0 && new_data != NULL) {
        fwrite(new_data, 1, new_len, f);
    }
    
    fclose(f);
    
    // Atomically rename temp file to final file
    // This ensures the file appears atomically to file watchers
    if (rename(temp_filepath, filepath) != 0) {
        LOG_ERROR("Failed to rename %s to %s: %s\n", temp_filepath, filepath, strerror(errno));
        unlink(temp_filepath); // Clean up temp file on failure
        return;
    }
    
    LOG_VERBOSE("[NATIVE_AGENT] Successfully wrote diff file: %s (old_len=%d, new_len=%d)\n",
                filepath, old_len, new_len);
}

// JVMTI function wrappers
jvmtiError SetEventCallbacks(jvmtiEnv* env, const jvmtiEventCallbacks* callbacks, jint size_of_callbacks) {
    if (callbacks == NULL || callbacks->ClassFileLoadHook == NULL) {
        return original_SetEventCallbacks(env, callbacks, size_of_callbacks);
    }
    
    // Obtain agent name from shared library using dladdr
    Dl_info dlinfo;
    if (dladdr((void*)callbacks->ClassFileLoadHook, &dlinfo) != 0 && dlinfo.dli_fname) {
        const char* filename = strrchr(dlinfo.dli_fname, '/');
        filename = filename ? filename + 1 : dlinfo.dli_fname;
    }
    
    if (next_agent_slot >= MAX_AGENTS) {
        LOG_ERROR("Maximum number of agents (%d) reached\n", MAX_AGENTS);
        return JVMTI_ERROR_OUT_OF_MEMORY;
    }
    
    // Get the next available agent slot
    int agent_index = next_agent_slot;
    ClassFileLoadHookInfo* info = &agent_info[agent_index];
    
    // Store the original callback
    info->callback = callbacks->ClassFileLoadHook;
    
    // Initialize with default name, then try to extract from library
    strncpy(info->name, "agent", sizeof(info->name) - 1);
    info->name[sizeof(info->name) - 1] = '\0';
    
    // Extract agent name from library path
    if (dlinfo.dli_fname) {
        extract_agent_name(info->name, sizeof(info->name), dlinfo.dli_fname);
    }

    next_agent_slot++;
    LOG_NORMAL("[NATIVE_AGENT] Registered agent %s at index %d (total: %d)\n",
               info->name, agent_index, next_agent_slot);
    
    // Register the pre-generated wrapper function for this agent
    *((jvmtiEventClassFileLoadHook*)&(callbacks->ClassFileLoadHook)) = (jvmtiEventClassFileLoadHook)wrapper_functions[agent_index];

    jvmtiError result = original_SetEventCallbacks(env, callbacks, size_of_callbacks);
    
    if (result == JVMTI_ERROR_NONE) {
        LOG_NORMAL("[NATIVE_AGENT] Successfully registered wrapper_%d for agent %s\n",
                   agent_index, info->name);
    } else {
        LOG_ERROR("Failed to register wrapper for agent %s: JVMTI error %d\n", 
                  info->name, result);
        next_agent_slot--;  // Roll back on failure
    }
    return result;
}

// Agent lifecycle functions
JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    jint res;

    // Check if agent is already loaded
    if (__sync_fetch_and_add(&agent_already_loaded, 1) != 0) {
        fprintf(stderr, "ERROR: Native-agent is already loaded! Each JVM process should only load this agent once.\n");
        return JNI_ERR;
    }

    // Parse agent options (includes NATIVE_WRAPPER_ARGS env var)
    int parse_result = parse_agent_options(options);
    if (parse_result == 1) {
        // Help requested
        display_help();
        agent_already_loaded = 0;  // Reset flag so agent can be loaded again if needed
        return JNI_OK;  // Exit gracefully after showing help
    } else if (parse_result < 0) {
        // Parse error
        agent_already_loaded = 0;  // Reset flag
        return JNI_ERR;
    }

    // Setup communication directories
    if (setup_directories(getpid()) != 0) {
        agent_already_loaded = 0;  // Reset flag
        return JNI_ERR;
    }

    // Log configuration
    log_configuration();

    // Initialize wrapper function pointer array
    initialize_wrapper_functions();
    LOG_VERBOSE("[NATIVE_AGENT] Initialized %d wrapper functions\n", MAX_AGENTS);
    
    jvmtiEnv *jvmti = NULL;
    res = (*vm)->GetEnv(vm, (void**)&jvmti, JVMTI_VERSION_1_2);
    if (res != JNI_OK || jvmti == NULL) {
        fprintf(stderr, "ERROR: Unable to get JVMTI environment (res=%d)\n", res);
        cleanup_directories();
        agent_already_loaded = 0;  // Reset flag
        return JNI_ERR;
    }

    // Store original function pointer before wrapping
    original_SetEventCallbacks = (*jvmti)->SetEventCallbacks;
    
    if (original_SetEventCallbacks == NULL) {
        fprintf(stderr, "ERROR: SetEventCallbacks function pointer is NULL\n");
        cleanup_directories();
        agent_already_loaded = 0;  // Reset flag
        return JNI_ERR;
    }
    
    // Replace with our wrapper
    *(void**)&((*jvmti)->SetEventCallbacks) = (void*)&SetEventCallbacks;
    
    LOG_NORMAL("[NATIVE_AGENT] Coordinator loaded successfully!\n");
    return JNI_OK;
}

JNIEXPORT void JNICALL Agent_OnUnload(JavaVM *vm) {
    // Restore original JVMTI function pointer
    jvmtiEnv* jvmti = NULL;
    if ((*vm)->GetEnv(vm, (void**)&jvmti, JVMTI_VERSION_1_2) == JNI_OK && jvmti != NULL) {
        *(void**)&((*jvmti)->SetEventCallbacks) = (void*)original_SetEventCallbacks;
        LOG_VERBOSE("[NATIVE_AGENT] Restored original SetEventCallbacks function pointer\n");
    }
    
    // Clean up communication directories
    cleanup_directories();
    
    // Reset state
    memset(agent_info, 0, sizeof(agent_info));
    next_agent_slot = 0;
    file_counter = 0;
    agent_already_loaded = 0;
}