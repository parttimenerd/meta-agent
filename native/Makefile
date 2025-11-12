# Makefile for JVMTI agents (supports macOS and Linux)

# Detect operating system
UNAME_S := $(shell uname -s)

# Variables
CC = gcc
CFLAGS = -shared -fPIC -Wall -Wextra -Wno-unused-parameter

# OS-specific settings
ifeq ($(UNAME_S),Darwin)
    # macOS
    EXT = dylib
    JAVA_HOME ?= $(shell /usr/libexec/java_home)
    JAVA_INCLUDE = -I"$(JAVA_HOME)/include" -I"$(JAVA_HOME)/include/darwin"
else ifeq ($(UNAME_S),Linux)
    # Linux
    EXT = so
    JAVA_HOME ?= $(shell dirname $$(dirname $$(readlink -f $$(which javac))))
    JAVA_INCLUDE = -I"$(JAVA_HOME)/include" -I"$(JAVA_HOME)/include/linux"
else
    # Other Unix (fallback to .so)
    EXT = so
    JAVA_INCLUDE = -I"$(JAVA_HOME)/include"
endif

# Find all C source files and generate corresponding target names
C_SOURCES = $(wildcard *.c)
C_TARGETS = $(C_SOURCES:%.c=lib%.$(EXT))
TARGETS = $(C_TARGETS)

# Default target
all: $(TARGETS)

# Pattern rule to build shared libraries from C files
lib%.$(EXT): %.c
	$(CC) $(CFLAGS) $(JAVA_INCLUDE) -o $@ $<

clean:
	rm -f *.dylib *.so

# Test target to verify JAVA_HOME is set correctly
test-java-home:
	@echo "Operating System: $(UNAME_S)"
	@echo "Library Extension: $(EXT)"
	@echo "JAVA_HOME: $(JAVA_HOME)"
	@echo "Java include directories:"
ifeq ($(UNAME_S),Darwin)
	@ls -la "$(JAVA_HOME)/include" 2>/dev/null || echo "Java include directory not found!"
	@ls -la "$(JAVA_HOME)/include/darwin" 2>/dev/null || echo "Darwin include directory not found!"
else ifeq ($(UNAME_S),Linux)
	@ls -la "$(JAVA_HOME)/include" 2>/dev/null || echo "Java include directory not found!"
	@ls -la "$(JAVA_HOME)/include/linux" 2>/dev/null || echo "Linux include directory not found!"
else
	@ls -la "$(JAVA_HOME)/include" 2>/dev/null || echo "Java include directory not found!"
endif

# Help target
help:
	@echo "Available targets:"
	@echo "  all          - Build the JVMTI agent (default)"
	@echo "  clean        - Remove built files"
	@echo "  test-java-home - Check if JAVA_HOME is set correctly"
	@echo "  help         - Show this help message"
	@echo ""
	@echo "Current OS: $(UNAME_S)"
	@echo "Library extension: $(EXT)"
	@echo ""
	@echo "Usage:"
	@echo "  make                    # Build with auto-detected JAVA_HOME"
	@echo "  make JAVA_HOME=/path    # Build with specific JAVA_HOME"
	@echo "  make clean              # Clean built files"

.PHONY: all clean test-java-home help
