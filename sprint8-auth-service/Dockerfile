# ==============================================================================
# GENERIC TEMPLATE — Node.js service (npm), with an optional build step
# (TypeScript/NestJS/webpack/etc.). Project-agnostic: no hardcoded package
# name, entry file, or framework assumptions beyond "npm install + optional
# build + npm start" — copy it into any Node project with a package.json and
# it works as-is.
#
# What you'd change per project — all as --build-arg flags, no editing:
#   NODE_VERSION   Node major version to build/run on         (default 20)
#   APP_PORT       port the app listens on                    (default 3000)
#   NEEDS_BUILD    "true" to run `npm run build` (TS/Nest/etc.),
#                  "false" to skip it for a plain JS project   (default true)
#   BUILD_OUTPUT   folder `npm run build` writes to, copied
#                  into the runtime stage                      (default dist)
#   START_CMD      command the container runs on boot          (default "node dist/main.js")
#   PERSIST_DIR    a directory the app writes to at runtime that
#                  must be writable if a volume is mounted over it
#                  (e.g. generated keys, uploads); "" to skip
#                  (default "login-keys" — this project's RSA keypair dir)
#
#   docker build --build-arg APP_PORT=3000 --build-arg NEEDS_BUILD=false \
#                --build-arg START_CMD="node index.js" -t my-service .
#   docker run -p 3000:3000 my-service
# ==============================================================================

ARG NODE_VERSION=20

# ---------- Stage 1: install deps + build (TS -> JS, if applicable) ---------
FROM node:${NODE_VERSION}-alpine AS build
WORKDIR /app

# package*.json before source: this dependency-install layer is cached and
# reused as long as dependencies haven't changed, even if source has.
COPY package*.json ./
RUN npm ci --no-fund --no-audit

# Config files a build step needs (tsconfig, nest-cli.json, etc.) plus the
# ones that always exist. Wildcards so a missing file just matches nothing
# instead of failing the COPY.
COPY tsconfig*.json* nest-cli.json* ./
COPY src ./src

ARG NEEDS_BUILD=true
# Projects with no build step (plain JS) still hit this RUN, but the shell
# conditional turns it into a no-op instead of failing on a missing script.
RUN if [ "$NEEDS_BUILD" = "true" ]; then npm run build; fi

# ---------- Stage 2: runtime — prod deps only, no dev tooling ---------------
FROM node:${NODE_VERSION}-alpine AS runtime
ENV NODE_ENV=production
WORKDIR /app

# Reinstall from package*.json rather than pruning what stage 1 has: this is
# the reliable way to end up with a node_modules that contains ONLY
# production dependencies, regardless of what devDependencies stage 1
# needed to run its build.
COPY package*.json ./
RUN npm ci --omit=dev --no-fund --no-audit

ARG NEEDS_BUILD=true
ARG BUILD_OUTPUT=dist
# For a build-based project, bring in the compiled output. For a plain JS
# project (NEEDS_BUILD=false), bring in the source directly instead — it IS
# the runnable app, there's nothing to compile.
COPY --from=build /app/${BUILD_OUTPUT} ./${BUILD_OUTPUT}

# Uncomment (or pass at build time) if NEEDS_BUILD=false and your app's
# entry point lives in src/ rather than a build-output folder:
#   COPY --from=build /app/src ./src

# A directory the app writes to at runtime (e.g. a generated keypair). Must
# exist and be owned by the runtime user *before* USER switches away from
# root, so that a volume later mounted on top of it inherits the right
# ownership instead of root's.
ARG PERSIST_DIR="login-keys"
RUN if [ -n "$PERSIST_DIR" ]; then mkdir -p "$PERSIST_DIR"; fi \
    && chown -R node:node /app

ARG APP_PORT=3000
ENV APP_PORT=${APP_PORT}
EXPOSE ${APP_PORT}

USER node

ARG START_CMD="node dist/main.js"
ENV START_CMD=${START_CMD}
ENTRYPOINT ["sh", "-c", "$START_CMD"]
