#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Mushroom Offline Database - Fix UNIQUE Error & Resume Builder
Runs the full offline database builder with automatic UNIQUE schema migration,
instant donor cloning, full curated knowledge, and compression support.
"""

import os
import sys

# Ensure scripts directory is in sys.path
script_dir = os.path.dirname(os.path.abspath(__file__))
if script_dir not in sys.path:
    sys.path.insert(0, script_dir)

from build_offline_db import main

if __name__ == "__main__":
    main()
