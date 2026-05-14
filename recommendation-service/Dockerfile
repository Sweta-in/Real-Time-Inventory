# ── Stage 1: Build ──
FROM python:3.10-slim AS builder
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --prefix=/install -r requirements.txt
# Pre-download the sentence-transformers model
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('all-MiniLM-L6-v2')" || true

# ── Stage 2: Runtime ──
FROM python:3.10-slim
WORKDIR /app
COPY --from=builder /install /usr/local
COPY --from=builder /root/.cache /root/.cache
COPY . .
RUN mkdir -p /app/model/artifacts
EXPOSE 8082
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8082"]
