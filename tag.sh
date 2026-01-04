#!/bin/bash

set -e

SCRIPT_PATH=$(realpath "${BASH_SOURCE[0]}")
SCRIPT_DIR=$(dirname "${SCRIPT_PATH}")
cd "$SCRIPT_DIR"

./gradlew tag