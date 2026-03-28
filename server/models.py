from typing import Optional

from pydantic import BaseModel, ConfigDict, Field, model_validator


class RequestBundle(BaseModel):
    """Bundle sent by an Android node to request content from the server."""

    model_config = ConfigDict(frozen=True)

    node_id: str
    query_id: str
    query_string: str  # plain text → search; starts with "/" → article fetch
    timestamp: int = Field(gt=0)  # Unix seconds
    ttl_seconds: int = Field(gt=0)
    hop_count: int = Field(ge=0)
    signature: Optional[str] = None  # base64 Ed25519; None until issue #9 signs it


class ResponseBundle(BaseModel):
    """One chunk of a server response to a RequestBundle."""

    model_config = ConfigDict(frozen=True)

    server_id: str
    query_id: str  # matches the originating RequestBundle.query_id
    chunk_index: int = Field(ge=0)
    total_chunks: int = Field(ge=1)
    content_type: str
    payload_b64: str  # base64-encoded bytes for this chunk
    sha256: str  # hex SHA-256 of the decoded chunk payload for per-chunk integrity
    signature: Optional[str] = None  # base64 Ed25519; None until issue #9 signs it

    @model_validator(mode="after")
    def chunk_index_within_bounds(self) -> "ResponseBundle":
        if self.chunk_index >= self.total_chunks:
            raise ValueError(
                f"chunk_index ({self.chunk_index}) must be less than "
                f"total_chunks ({self.total_chunks})"
            )
        return self
