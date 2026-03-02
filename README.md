# Roamly

A self-hosted location tracking application.

## Configuration

All configuration is done through environment variables. Copy `.env.example` to `.env` and fill in the values, or set them directly in your hosting platform (e.g. Portainer stack environment variables).

| Variable | Required | Description |
|---|---|---|
| `SECRET_KEY` | Yes | Django secret key. Generate one at https://djecrety.ir/ |
| `DEBUG` | No | Set to `False` in production (default: `True`) |
| `POSTGRES_PASSWORD` | Yes | Password for the PostgreSQL database |
| `ALLOWED_HOSTS` | Yes | Comma-separated hostnames without protocol (e.g. `roamly.cyou,www.roamly.cyou`) |
| `CSRF_TRUSTED_ORIGINS` | Yes | Comma-separated origins with protocol (e.g. `https://roamly.cyou,https://www.roamly.cyou`) |

## Deployment

### Docker Compose (local)

```bash
cp .env.example .env
# Edit .env with your values
docker compose up -d
```

### Portainer

1. Create a new stack and paste the contents of `docker-compose.yml`
2. Under **Environment variables**, add each variable from `.env.example` with your values
3. Deploy the stack

No domain names are hardcoded — all host and CSRF configuration comes from environment variables.
