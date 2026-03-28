import httpx
import pytest

from kiwix_proxy import fetch_from_kiwix

SEARCH_HTML = b"<html><body>Search results for water cycle</body></html>"
ARTICLE_HTML = b"<html><body><h1>Water</h1><p>Article content...</p></body></html>"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _mock_transport(handler):
    """Wrap a sync request handler into an httpx.MockTransport."""
    return httpx.MockTransport(handler)


def _ok_handler(request: httpx.Request) -> httpx.Response:
    """Return canned HTML for search and article requests."""
    url = str(request.url)
    if "/search" in url:
        return httpx.Response(200, content=SEARCH_HTML, headers={"content-type": "text/html; charset=utf-8"})
    return httpx.Response(200, content=ARTICLE_HTML, headers={"content-type": "text/html; charset=utf-8"})


# ---------------------------------------------------------------------------
# Routing tests
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_search_query_routes_to_search_endpoint() -> None:
    """Plain-text query_string → GET /search?pattern=..."""

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/search"
        assert request.url.params["pattern"] == "water cycle"
        return httpx.Response(200, content=SEARCH_HTML, headers={"content-type": "text/html"})

    async with httpx.AsyncClient(transport=_mock_transport(handler), base_url="http://kiwix") as client:
        body, ct = await fetch_from_kiwix(client, "water cycle")

    assert body == SEARCH_HTML
    assert "text/html" in ct


@pytest.mark.asyncio
async def test_article_path_routes_directly() -> None:
    """'/' prefix query_string → GET {path} on kiwix-serve."""

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/wikipedia_en_all/A/Water"
        return httpx.Response(200, content=ARTICLE_HTML, headers={"content-type": "text/html"})

    async with httpx.AsyncClient(transport=_mock_transport(handler), base_url="http://kiwix") as client:
        body, ct = await fetch_from_kiwix(client, "/wikipedia_en_all/A/Water")

    assert body == ARTICLE_HTML
    assert "text/html" in ct


# ---------------------------------------------------------------------------
# Content-type fallback
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_missing_content_type_defaults_to_octet_stream() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=b"binary blob")

    async with httpx.AsyncClient(transport=_mock_transport(handler), base_url="http://kiwix") as client:
        _, ct = await fetch_from_kiwix(client, "anything")

    assert ct == "application/octet-stream"


# ---------------------------------------------------------------------------
# Error propagation
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_404_raises_http_status_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404)

    async with httpx.AsyncClient(transport=_mock_transport(handler), base_url="http://kiwix") as client:
        with pytest.raises(httpx.HTTPStatusError) as exc_info:
            await fetch_from_kiwix(client, "/nonexistent/article")

    assert exc_info.value.response.status_code == 404


@pytest.mark.asyncio
async def test_500_raises_http_status_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500)

    async with httpx.AsyncClient(transport=_mock_transport(handler), base_url="http://kiwix") as client:
        with pytest.raises(httpx.HTTPStatusError) as exc_info:
            await fetch_from_kiwix(client, "some query")

    assert exc_info.value.response.status_code == 500
