from __future__ import annotations

import json
import os
import subprocess
import tempfile
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Header, Request

app = FastAPI(title="OriginGuard C2PA Sidecar", version="0.1.0")


def _binary() -> Path | None:
    configured = os.getenv("C2PATOOL_PATH", "").strip()
    if not configured:
        return None
    path = Path(configured)
    return path if path.is_file() else None


def _validation_entries(payload: dict[str, Any]) -> list[dict[str, Any]]:
    raw = payload.get("validation_status") or payload.get("validationStatus") or []
    return [entry for entry in raw if isinstance(entry, dict)] if isinstance(raw, list) else []


def _manifest_store(payload: dict[str, Any]) -> dict[str, Any]:
    store = payload.get("manifest_store") or payload.get("manifestStore") or payload
    return store if isinstance(store, dict) else {}


def normalize(payload: dict[str, Any]) -> dict[str, Any]:
    store = _manifest_store(payload)
    manifests = store.get("manifests") if isinstance(store.get("manifests"), dict) else {}
    active_label = str(store.get("active_manifest") or store.get("activeManifest") or "")
    active = manifests.get(active_label, {}) if active_label else {}
    if not isinstance(active, dict):
        active = {}
    validations = _validation_entries(payload)
    # c2patool exposes validation_status for validation problems; a clean
    # credential normally has no entries in this collection.
    errors = validations
    credential_present = bool(manifests or active_label)

    claim_generator = str(active.get("claim_generator") or active.get("claimGenerator") or "")
    signature = active.get("signature_info") or active.get("signatureInfo") or {}
    if not isinstance(signature, dict):
        signature = {}
    signer = str(signature.get("common_name") or signature.get("commonName")
                 or signature.get("issuer") or claim_generator)
    title = str(active.get("title") or "")
    assertions = active.get("assertions") if isinstance(active.get("assertions"), list) else []
    actions: list[Any] = []
    for assertion in assertions:
        if not isinstance(assertion, dict):
            continue
        label = str(assertion.get("label", ""))
        data = assertion.get("data")
        if "actions" in label and isinstance(data, dict) and isinstance(data.get("actions"), list):
            actions.extend(data["actions"])

    if not credential_present:
        status = "NOT_FOUND"
    elif errors:
        status = "INVALID"
    else:
        status = "VERIFIED"
    return {
        "status": status,
        "credentialPresent": credential_present,
        "manifestCount": len(manifests),
        "activeManifest": active_label,
        "title": title,
        "signer": signer,
        "claimGenerator": claim_generator,
        "actions": actions,
        "validationStatus": validations,
        "limitations": [
            "C2PA only verifies the credential, asset binding and recorded assertions; it does not prove that visual content is true or AI-generated."
        ],
    }


@app.get("/health")
def health() -> dict[str, Any]:
    binary = _binary()
    return {"status": "ok", "configured": binary is not None, "binary": str(binary or "")}


@app.post("/v1/verify")
async def verify(request: Request, x_filename: str | None = Header(default=None)) -> dict[str, Any]:
    binary = _binary()
    if binary is None:
        return {
            "status": "NOT_CONFIGURED", "credentialPresent": False, "manifestCount": 0,
            "activeManifest": "", "signer": "", "actions": [], "validationStatus": [],
            "limitations": ["c2patool is not installed or C2PATOOL_PATH is not configured."],
        }
    suffix = Path(x_filename or "asset.bin").suffix[:12] or ".bin"
    content = await request.body()
    with tempfile.TemporaryDirectory(prefix="originguard-c2pa-") as directory:
        asset_path = Path(directory) / f"asset{suffix}"
        asset_path.write_bytes(content)
        try:
            completed = subprocess.run(
                [str(binary), str(asset_path)], capture_output=True, text=True,
                encoding="utf-8", errors="replace", timeout=45, check=False,
            )
        except subprocess.TimeoutExpired:
            return {"status": "UNAVAILABLE", "credentialPresent": False, "error": "verification timeout"}
        output = completed.stdout.strip()
        if not output:
            diagnostic = completed.stderr.strip()[-800:]
            lowered = diagnostic.lower()
            if "no claim found" in lowered or ("manifest" in lowered and "not" in lowered):
                return normalize({})
            return {"status": "UNAVAILABLE", "credentialPresent": False, "error": diagnostic or "empty c2patool output"}
        try:
            payload = json.loads(output)
        except json.JSONDecodeError:
            return {"status": "UNAVAILABLE", "credentialPresent": False, "error": "c2patool returned invalid JSON"}
        return normalize(payload if isinstance(payload, dict) else {})
