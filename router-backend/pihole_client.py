from __future__ import annotations
"""
pihole_client.py -- Pi-hole v6 REST API client (session-based auth).

Pi-hole v6 completely rewrote its API as REST with session-based auth:
  1. POST /api/auth with {"password": "..."} -> returns a session ID (SID)
  2. Subsequent requests pass that SID (as the "sid" query param below --
     verify this against your own Pi-hole's live docs at
     http://pi.hole/api/docs, self-hosted and guaranteed to match your
     exact version, since API details have changed across v6 releases)
  3. Sessions expire -- this client re-authenticates automatically when
     a request comes back 401.

If PIHOLE_PASSWORD isn't set (Pi-hole with no password configured),
skips auth entirely and calls the API unauthenticated, per Pi-hole's
documented behavior for that case.
"""

import requests

AUTH_TIMEOUT_SECONDS = 10
REQUEST_TIMEOUT_SECONDS = 10


class PiholeClient:
    def __init__(self, base_url: str, password: str | None):
        self.base_url = base_url.rstrip("/")
        self.password = password
        self._sid: str | None = None

    def _authenticate(self) -> bool:
        if not self.password:
            return True  # no password configured on Pi-hole's side -- nothing to do

        try:
            response = requests.post(
                f"{self.base_url}/api/auth",
                json={"password": self.password},
                timeout=AUTH_TIMEOUT_SECONDS,
            )
            if response.status_code != 200:
                self._sid = None
                return False
            data = response.json()
            self._sid = data.get("session", {}).get("sid")
            return self._sid is not None
        except requests.RequestException:
            self._sid = None
            return False
        except ValueError:
            # Response wasn't valid JSON
            self._sid = None
            return False

    def _get(self, path: str, params: dict | None = None) -> dict | None:
        """GET with automatic auth -- authenticates first if we don't have
        a session yet, retries once if the existing session expired."""
        if self.password and self._sid is None:
            if not self._authenticate():
                return None

        params = dict(params or {})
        if self._sid:
            params["sid"] = self._sid

        try:
            response = requests.get(
                f"{self.base_url}{path}", params=params, timeout=REQUEST_TIMEOUT_SECONDS,
            )
            if response.status_code == 401 and self.password:
                # Session expired -- re-authenticate once and retry.
                if self._authenticate():
                    params["sid"] = self._sid
                    response = requests.get(
                        f"{self.base_url}{path}", params=params, timeout=REQUEST_TIMEOUT_SECONDS,
                    )
                else:
                    return None
            if response.status_code != 200:
                return None
            return response.json()
        except requests.RequestException:
            return None
        except ValueError:
            return None

    def recent_queries(self, length: int = 100) -> list[dict]:
        """Recent DNS queries. Endpoint/field names per Pi-hole v6's REST
        API (GET /api/queries) -- verify the exact response shape against
        your instance's own docs (http://pi.hole/api/docs) the first time
        you run this live, and adjust the field access in
        pihole_poller.py if they differ."""
        data = self._get("/api/queries", params={"length": length})
        if data is None:
            return []
        return data.get("queries", [])
