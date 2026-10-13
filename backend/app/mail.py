"""Outgoing email, behind one small seam. Only transactional mail the person asked for (password
reset, address verification) is ever sent; there is no marketing path here."""

import logging
from typing import Protocol

import httpx

logger = logging.getLogger("app.mail")


class Mailer(Protocol):
    async def send(self, *, to: str, subject: str, text: str) -> bool:
        """True when the provider accepted the message. Never raises for provider trouble."""
        ...


class ResendMailer:
    """Resend's REST API over httpx (one call, no SDK)."""

    def __init__(
        self,
        api_key: str,
        sender: str,
        *,
        base_url: str = "https://api.resend.com/emails",
        timeout_seconds: float = 10.0,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._api_key = api_key
        self._sender = sender
        self._base_url = base_url
        self._client = httpx.AsyncClient(timeout=timeout_seconds, transport=transport)

    async def aclose(self) -> None:
        await self._client.aclose()

    async def send(self, *, to: str, subject: str, text: str) -> bool:
        try:
            response = await self._client.post(
                self._base_url,
                headers={"Authorization": f"Bearer {self._api_key}"},
                json={"from": self._sender, "to": [to], "subject": subject, "text": text},
            )
        except httpx.HTTPError as error:
            logger.warning("mail_unreachable", extra={"error_type": type(error).__name__})
            return False
        if response.status_code >= 300:
            logger.warning("mail_rejected", extra={"status": response.status_code})
            return False
        return True
