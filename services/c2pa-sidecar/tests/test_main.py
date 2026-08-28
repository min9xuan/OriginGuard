from originguard_c2pa_sidecar.main import normalize


def test_missing_manifest_is_not_found_not_invalid() -> None:
    result = normalize({})
    assert result["status"] == "NOT_FOUND"
    assert result["credentialPresent"] is False


def test_manifest_is_normalized() -> None:
    result = normalize({"manifest_store": {"active_manifest": "urn:test", "manifests": {
        "urn:test": {"title": "sample.png", "claim_generator": "Example signer"}
    }}})
    assert result["status"] == "VERIFIED"
    assert result["manifestCount"] == 1
    assert result["signer"] == "Example signer"


def test_signature_common_name_is_preferred_as_signer() -> None:
    result = normalize({"active_manifest": "urn:test", "manifests": {
        "urn:test": {"claim_generator": "Generator", "signature_info": {"common_name": "Signer"}}
    }})
    assert result["signer"] == "Signer"
