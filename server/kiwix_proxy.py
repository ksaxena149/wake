import logging

import httpx

logger = logging.getLogger(__name__)


async def fetch_from_kiwix(
    client: httpx.AsyncClient,
    query_string: str,
) -> tuple[bytes, str]:
    """Proxy a request to kiwix-serve and return (body_bytes, content_type).

    Routing: '/' prefix → article path fetch; otherwise → search query.
    Raises httpx.HTTPStatusError on non-2xx responses.
    """
    if query_string.startswith("/"):
        resp = await client.get(query_string)
    else:
        resp = await client.get("/search", params={"pattern": query_string})

    resp.raise_for_status()
    content_type = resp.headers.get("content-type", "application/octet-stream")
    return resp.content, content_type
