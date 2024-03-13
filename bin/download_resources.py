#!/usr/bin/env python3

"""
Download resources from the web and save them to the resources folder.
"""

from pathlib import Path
import os
from typing import Dict

import requests

resources_folder = Path(__file__).parent.parent / 'src/main/resources'

resources: Dict[str, str] = {
    "highlight.css": "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/styles/github.min.css",
    "diff2html.css": "https://cdn.jsdelivr.net/npm/diff2html/bundles/css/diff2html.min.css",
    "diff2html.js": "https://cdn.jsdelivr.net/npm/diff2html/bundles/js/diff2html-ui.min.js",
    "highlight.js": "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js",
    "highlight.java.js": "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/java.min.js",
}

os.makedirs(resources_folder, exist_ok=True)

# Download the resources
for resource, url in resources.items():
    target_file = resources_folder / resource
    print(f"Downloading {resource} from {url} to {target_file}")
    response = requests.get(url)
    response.raise_for_status()
    with open(target_file, 'w') as file:
        file.write(response.text)