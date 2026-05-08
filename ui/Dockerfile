# Stage 1 — build the static SPA bundle.
FROM node:20-alpine AS builder
WORKDIR /app

# Build args injected by docker-compose. VITE_* vars are baked into the bundle at
# build time; rebuild the image when the API key changes.
ARG VITE_MARIAALPHA_API_KEY
ARG VITE_API_BASE_URL=

# Copy manifest first so npm ci is layer-cacheable independent of source changes.
COPY ui/package.json ui/package-lock.json ./
RUN npm ci

COPY ui/ ./
ENV VITE_MARIAALPHA_API_KEY=${VITE_MARIAALPHA_API_KEY}
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
RUN npm run build

# Stage 2 — serve the static bundle behind nginx with a reverse proxy for /api + /ws.
FROM nginx:1.27-alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY ui/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost/ || exit 1
