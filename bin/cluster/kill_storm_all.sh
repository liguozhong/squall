#!/bin/bash

STORM_INSTALL_DIR=/localhome/datalab/avitorovic/storm

# Killing Storm on master
$STORM_INSTALL_DIR/bin/kill_storm_local.sh

# Killing Storm on blades
cexec $STORM_INSTALL_DIR/bin/kill_storm_local.sh
