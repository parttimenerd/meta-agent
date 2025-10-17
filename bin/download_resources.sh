#!/usr/bin/env bash

# Download resources from the web and save them to the resources folder.

set -e

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESOURCES_FOLDER="$SCRIPT_DIR/../src/main/resources"

# Create resources folder if it doesn't exist
mkdir -p "$RESOURCES_FOLDER"

# Define resources as associative array (requires bash 4+)
# Format: [filename]="url"
declare -A resources=(
    ["highlight.css"]="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/styles/github.min.css"
    ["diff2html.css"]="https://cdn.jsdelivr.net/npm/diff2html/bundles/css/diff2html.min.css"
    ["diff2html.js"]="https://cdn.jsdelivr.net/npm/diff2html/bundles/js/diff2html-ui.min.js"
    ["highlight.js"]="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"
    ["highlight.java.js"]="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/java.min.js"
)

# Download the resources
for resource in "${!resources[@]}"; do
    url="${resources[$resource]}"
    target_file="$RESOURCES_FOLDER/$resource"

    echo "Downloading $resource from $url to $target_file"

    # Use curl to download (with -f to fail on HTTP errors, -L to follow redirects, -s for silent mode with -S to show errors)
    if ! curl -fsSL "$url" -o "$target_file"; then
        echo "Error: Failed to download $resource from $url" >&2
        exit 1
    fi
done

echo "All resources downloaded successfully!"
