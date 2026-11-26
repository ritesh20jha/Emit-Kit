# GEN-12 toolchain image (spec 5.6). Run from the repo root; deploy/docker/toolchain is the build context:
#   docker build -t emitkit/toolchain:test deploy/docker/toolchain
# Final stage is the pinned python:3.12 base with Node 20 and Temurin 21 copied in from pinned images.
# All tools are baked in: the container runs with --network none.

# Global Node tools. Installed under /opt/node so the final stage copies only the modules, not npm.
FROM node:20.20.2-bookworm-slim AS nodetools
RUN npm install -g --prefix /opt/node \
      typescript@5.9.3 prettier@3.10.1 @types/node@20.19.43 \
      commander@11.1.0 @modelcontextprotocol/sdk@1.32.2 \
 && npm cache clean --force

# Python tools in a venv, so the final stage can drop pip.
FROM python:3.12.16-slim-bookworm AS pytools
RUN python -m venv /opt/venv \
 && /opt/venv/bin/pip install --no-cache-dir ruff==0.17.5 httpx==0.28.1 pydantic==2.14.4 \
 && rm -rf /opt/venv/bin/pip* /opt/venv/lib/python3.12/site-packages/pip*

# Jackson 2 jars for the javac classpath and the google-java-format jar.
FROM gradle:8.14.7-jdk21-jammy AS vendor
WORKDIR /src
COPY vendor/ ./
RUN gradle --no-daemon -q vendorJars toolJars

FROM python:3.12.16-slim-bookworm
COPY --from=nodetools /usr/local/bin/node /usr/local/bin/node
COPY --from=nodetools /opt/node /opt/node
COPY --from=eclipse-temurin:21.0.9_10-jdk-jammy /opt/java/openjdk /opt/java/openjdk
COPY --from=pytools /opt/venv /opt/venv
COPY --from=vendor /src/build/vendor /opt/vendor
COPY --from=vendor /src/build/tools /opt/tools
COPY google-java-format /usr/local/bin/google-java-format
COPY --chmod=0755 check.sh /usr/local/bin/check.sh
# ruff reads its user-level config from $XDG_CONFIG_HOME/ruff/ruff.toml when a project has no config.
COPY ruff.toml /etc/xdg/ruff/ruff.toml

# NODE_PATH serves node at runtime. tsc ignores NODE_PATH, so /node_modules (an ancestor of every
# /work path) links to the global modules for module resolution and @types auto-inclusion.
RUN ln -s /opt/node/lib/node_modules /node_modules \
 && rm -rf /usr/local/bin/pip* /usr/local/lib/python3.12/site-packages/pip* \
          /usr/local/lib/python3.12/site-packages/setuptools* /usr/local/lib/python3.12/site-packages/wheel* \
          /usr/bin/apt* /usr/bin/dpkg* \
 && mkdir -p /work && chown 10001:10001 /work

ENV JAVA_HOME=/opt/java/openjdk \
    NODE_PATH=/opt/node/lib/node_modules \
    VIRTUAL_ENV=/opt/venv \
    HOME=/work \
    XDG_CONFIG_HOME=/etc/xdg \
    PATH=/opt/venv/bin:/opt/java/openjdk/bin:/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

USER 10001
ENTRYPOINT ["/usr/local/bin/check.sh"]
