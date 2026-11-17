#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-only

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
exec java -jar "$SCRIPT_DIR/target/oepnv-victim.jar" "$@"
