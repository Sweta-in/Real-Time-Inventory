"""
Sentence-transformer embedder for semantic search.
Uses all-MiniLM-L6-v2 (384-dim embeddings).
"""
import logging
from sentence_transformers import SentenceTransformer

logger = logging.getLogger(__name__)


class Embedder:
    def __init__(self, model_name: str = "all-MiniLM-L6-v2"):
        logger.info(f"Loading sentence-transformer model: {model_name}")
        self.model = SentenceTransformer(model_name)
        self.dimension = 384
        logger.info(f"Embedder loaded. Dimension: {self.dimension}")

    def embed(self, text: str) -> list[float]:
        """Generate embedding vector for a single text string."""
        embedding = self.model.encode(text, normalize_embeddings=True)
        return embedding.tolist()

    def embed_batch(self, texts: list[str]) -> list[list[float]]:
        """Generate embedding vectors for a batch of texts."""
        embeddings = self.model.encode(texts, normalize_embeddings=True, batch_size=32)
        return [e.tolist() for e in embeddings]
