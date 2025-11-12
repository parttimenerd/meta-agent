# Native Meta-Agent

This is the native agent of the [Meta-Agent](../README.md) project which
instruments other native agents that use the `ClassFileLoadHook` JVMTI callback.
This agent allows us to observe and record class file transformations performed by
any agent, as Java agents are all called by the same libinstrument agent.

To build the agent, just run `make` in this directory. I included a minimal
example agent ([`agent_minimal_cfh`](agent_minimal_cfh.c)) that uses `ClassFileLoadHook` to demonstrate
how to use the native helper.

The agent has to be loaded before any other agent to work correctly.

Example that logs all calls to any `ClassFileLoadHook` and emit lots of debug information:
```sh
> java -agentpath:./libnative_agent.dylib=always=true,log=verbose \
   -javaagent:../target/meta-agent.jar=server \
   -agentpath:libagent_minimal_cfh.dylib ../Loop.java
[NATIVE_AGENT] Loading native-agent (log_level=2, always=1, skip_count=0, comm_dir=/tmp/njvm39540)...
[NATIVE_AGENT] Initialized 4096 wrapper functions
[NATIVE_AGENT] Coordinator loaded successfully!
[NATIVE_AGENT] Extracted agent name 'instrument' from library '/Users/i560383_1/.sdkman/candidates/java/25-sapmchn/lib/libinstrument.dylib'
[NATIVE_AGENT] Registered agent instrument at index 0 (total: 1)
[NATIVE_AGENT] Successfully registered wrapper_0 for agent instrument
[Agent] Agent_OnLoad called.
[NATIVE_AGENT] Extracted agent name 'agent_minimal_cfh' from library '/Users/i560383_1/code/experiments/meta-agent/native/libagent_minimal_cfh.dylib'
[NATIVE_AGENT] Registered agent agent_minimal_cfh at index 1 (total: 2)
[NATIVE_AGENT] Successfully registered wrapper_1 for agent agent_minimal_cfh
[Agent] ClassFileLoadHook registered.
[NATIVE_AGENT] Agent agent_minimal_cfh (index=1) processing class: jdk/internal/vm/ContinuationSupport
[Agent] Class loaded: jdk/internal/vm/ContinuationSupport
[NATIVE_AGENT] Writing transformation to temp file: /tmp/njvm39540_tmp/0 (agent=agent_minimal_cfh, class=jdk/internal/vm/ContinuationSupport)
[NATIVE_AGENT] Successfully wrote diff file: /tmp/njvm39540/0 (old_len=971, new_len=971)
[NATIVE_AGENT] Agent agent_minimal_cfh (index=1) processing class: jdk/internal/vm/Continuation$Pinned
[Agent] Class loaded: jdk/internal/vm/Continuation$Pinned
...
[NATIVE_AGENT] Agent instrument (index=0) processing class: java/lang/invoke/StringConcatFactory$InlineHiddenClassStrategy$2
[NATIVE_AGENT] Agent agent_minimal_cfh (index=1) processing class: javassist/NotFoundException
[Agent] Class loaded: javassist/NotFoundException
[NATIVE_AGENT] Writing transformation to temp file: /tmp/njvm40045_tmp/421 (agent=agent_minimal_cfh, class=javassist/NotFoundException)
[NATIVE_AGENT] Successfully wrote diff file: /tmp/njvm40045/421 (old_len=1113, new_len=1113)
[NATIVE_AGENT] Agent instrument (index=0) processing class: javassist/NotFoundException
[NATIVE_AGENT] Writing transformation to temp file: /tmp/njvm40045_tmp/422 (agent=instrument, class=javassist/NotFoundException)
```

So can here, that we even observe transformations of classes like `Instrumentation`
that is used to implement Java agents. Also, you can observe that we capture
transformations from both the Java agent (named `instrument`) and the native
agent (named `agent_minimal_cfh`).

## Usage

Load the native helper with the JVM using `-agentpath` before other native agents that use `ClassFileLoadHook`. The helper accepts a few simple options; pass `=help` to print detailed usage.

```sh
> java -agentpath:./libnative_agent.dylib=help     

==============================================================================
  Native Agent - Help
==============================================================================

DESCRIPTION:
  Wraps multiple JVMTI agents using ClassFileLoadHook callbacks.
  This agent must be loaded FIRST before other ClassFileLoadHook agents.

USAGE:
  java -agentpath:<path>=<options> [other agents...] YourClass

AGENT OPTIONS (comma-separated):
  help
      Display this help message and exit.

  log=<level>
      Set logging verbosity.
      Values: silent (no logging)
              normal (normal logging, default)
              verbose (detailed debug information)
      Example: -agentpath:libnative_agent.dylib=log=verbose

  always=<value>
      Always generate diff files even when no transformation occurs.
      Values: true (always generate)
              false (only when transformed, default)
      Example: -agentpath:libnative_agent.dylib=always=true

  skip=<agent>
      Skip wrapping the specified instrumentation agent.
      Can be specified multiple times to skip multiple agents.
      Example: -agentpath:libnative_agent.dylib=skip=instrument
      to skip wrapping libinstrument (the native agent handling Java agents)

ENVIRONMENT VARIABLES:
  NATIVE_WRAPPER_ARGS
      Arguments prepended to agent options (same format as agent options).
      Agent options will override environment variable settings.
      Example: export NATIVE_WRAPPER_ARGS="log=verbose,always=1"

EXAMPLES:
  # Display help
  java -agentpath:./libnative_agent.dylib=help

  # Verbose logging with always generate files
  java -agentpath:./libnative_agent.dylib=log=verbose,always=1 \
       -agentpath:./libagent_minimal_cfh.dylib HelloWorld

FILE-BASED COMMUNICATION:
  Creates /tmp/njvm<pid>/ directory for communication with the meta-agent.
  Each transformation creates a numbered file with diff data.

==============================================================================
```

## File-based communication (format)

The native agent creates files atomically in the directory `/tmp/njvm<pid>/` whenever
a class file transformation is observed. The files are numbered and have the following format:

1. Line 1: `agent_name` (UTF-8 text, e.g. `agent_minimal_cfh`)
2. Line 2: `class_name` (UTF-8 text, internal JVM name such as `java/lang/String` or `unknown`)
3. Line 3: `old_len` (ASCII decimal, number of bytes of original class data)
4. Line 4: `new_len` (ASCII decimal, number of bytes of transformed class data)
5. Binary: `old_len` bytes containing the original class file bytes (may be zero)
6. Binary: `new_len` bytes containing the transformed class file bytes

Notes:

- Lines 1–4 are UTF-8 text terminated by `\n`.
- Binary sections follow immediately after the fourth line feed with no separator.

This is far easier than calling Java from native code during class loading, as
this can lead to deadlocks and other issues.

## Implementation

The implementation uses the fact that all native agents use the same
`jvmtiEnv` object provided by the JVM (at least in OpenJDK, this is implementation
specific).
We can therefore hot-patch the `SetEventCallbacks` function to wrap any
`ClassFileLoadHook` callback with our own function that records the
transformations and then calls the original callback.

You can see a basic version of this in the [`agent_simple_wrap.c`](agent_simple_wrap.c) file.

## License

See the project's top-level `LICENSE` file for license terms.