# Roamly

A self-hosted location tracking application.

[![Docker Hub](https://img.shields.io/docker/pulls/waxn/roamly)](https://hub.docker.com/r/waxn/roamly)

## Quick start (Docker Hub image)

The easiest way to run Roamly is to pull the pre-built image from Docker Hub — no need to clone the repo or build anything.

```bash
curl -o docker-compose.yml https://raw.githubusercontent.com/waxn/roamly/main/docker-compose.yml
cp .env.example .env   # or create .env manually — see Configuration below
# Edit .env with your values, then:
docker compose up -d
```

Or paste this minimal compose snippet into Portainer / your stack manager:

```yaml
services:
  redis:
    image: redis:7-alpine
    restart: unless-stopped

  web:
    image: waxn/roamly:latest
    restart: unless-stopped
    depends_on:
      - redis
    ports:
      - "8001:8000"
    volumes:
      - ./staticfiles:/app/staticfiles
      - ./media:/app/media
      - ./migrations:/app/tracker/migrations
    environment:
      - SECRET_KEY=${SECRET_KEY}
      - DEBUG=${DEBUG:-False}
      - DATABASE_URL=${DATABASE_URL}
      - ALLOWED_HOSTS=${ALLOWED_HOSTS}
      - CSRF_TRUSTED_ORIGINS=${CSRF_TRUSTED_ORIGINS}
      - REDIS_URL=redis://redis:6379/1
```

## Configuration

All configuration is done through environment variables. Copy `.env.example` to `.env` and fill in the values, or set them directly in your hosting platform (e.g. Portainer stack environment variables).

| Variable | Required | Description |
|---|---|---|
| `SECRET_KEY` | Yes | Django secret key. Generate one at https://djecrety.ir/ |
| `DEBUG` | No | Set to `False` in production (default: `True`) |
| `DATABASE_URL` | No | PostgreSQL/PostGIS connection string. Omit to use SQLite. |
| `POSTGRES_PASSWORD` | If using local db container | Password for the local PostGIS container |
| `ALLOWED_HOSTS` | Yes | Comma-separated hostnames without protocol (e.g. `roamly.example.com`) |
| `CSRF_TRUSTED_ORIGINS` | Yes | Comma-separated origins with protocol (e.g. `https://roamly.example.com`) |

## Deployment

### Portainer (recommended for self-hosters)

1. In Portainer, create a new **Stack**
2. Paste the minimal compose snippet from the Quick start section above
3. Under **Environment variables**, add each variable from the Configuration table with your values
4. Click **Deploy the stack**

To update to the latest image: pull the stack again or run `docker compose pull && docker compose up -d` on the host.

### Docker Compose (self-managed server)

```bash
# First deploy
cp .env.example .env
# Edit .env with your values
docker compose up -d

# Update to latest image
docker compose pull
docker compose up -d
```

### Building from source

If you want to run from source (e.g. for development or to test local changes):

```bash
git clone https://git.wafl.buzz/waxn/Roamly.git
cd Roamly
cp .env.example .env
# Edit .env
docker compose up -d --build
```

The `docker-compose.yml` in the repo uses `build: .` by default so it always builds locally. Replace `build: .` with `image: waxn/roamly:latest` under the `web` service to use the published image instead.

## CI/CD

Every push to `main` and every version tag (`v1.2.3`) automatically builds and pushes a multi-arch image (amd64 + arm64) to Docker Hub via GitHub Actions (`.github/workflows/docker-publish.yml`).

| Git event | Docker tags published |
|---|---|
| Push to `main` | `latest`, `main` |
| Tag `v1.2.3` | `1.2.3`, `1.2`, `latest` |

To pin to a specific release rather than tracking `latest`, use a version tag in your compose file:

```yaml
image: waxn/roamly:1.2.3
```
