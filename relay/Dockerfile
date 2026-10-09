# KEA relay. Stateless, in-memory, so the image stays tiny and cold starts
# are fast — which matters because every free tier sleeps.
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /srv

# Dependencies first so the layer caches across code edits.
# Runtime deps only: the test packages are ~12 MB the server never imports.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY relay.py .

# Never run as root.
RUN useradd --system --uid 10001 relay && chown -R relay:relay /srv
USER relay

ENV PORT=8080
EXPOSE 8080

# Respects $PORT, which every free host injects.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD python -c "import os,sys,urllib.request; p=os.environ.get('PORT','8080'); sys.exit(0 if urllib.request.urlopen(f'http://127.0.0.1:{p}/health',timeout=2).status==200 else 1)"

# One worker only: state lives in process memory. Scaling out would need a
# shared store (Redis pub/sub) plus sticky routing.
CMD ["sh", "-c", "exec python relay.py"]