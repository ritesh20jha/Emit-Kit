#!/bin/sh
# STUB (Task 1). Task 2 replaces this with the real Vault bootstrap.
# TODO(task 2): chmod 777 /secrets and chmod 644 every file written there (dev only;
# Docker creates the bind-mount root:root).
touch /tmp/ready && exec sleep infinity
