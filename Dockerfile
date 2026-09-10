FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
WORKDIR /app

# Install GeoDjango system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    binutils libproj-dev gdal-bin libgdal-dev libgeos-dev libpq-dev gcc \
    && rm -rf /var/lib/apt/lists/*

ENV CPLUS_INCLUDE_PATH=/usr/include/gdal
ENV C_INCLUDE_PATH=/usr/include/gdal

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .
RUN mkdir -p /app/staticfiles /app/media

EXPOSE 8000
# Kept in step with docker-compose.yml.example. gthread rather than the
# default sync worker: 3 sync workers is 3 concurrent requests for the whole
# app, and one slow streaming download takes a third of that.
CMD ["gunicorn", "roamly.wsgi:application", "--bind", "0.0.0.0:8000", \
     "--worker-class", "gthread", "--workers", "3", "--threads", "8", \
     "--timeout", "120", "--graceful-timeout", "30"]
