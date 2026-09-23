from __future__ import annotations

import copy
import hashlib
from pathlib import Path
from typing import Any

import yaml
from mkdocs.exceptions import PluginError
from openapi_spec_validator import validate
from openapi_spec_validator.readers import read_from_filename


ROOT = Path(__file__).resolve().parent
SPEC_PATH = ROOT / "docs" / "api" / "openapi.yaml"
VENDOR_PATH = ROOT / "docs" / "assets" / "swagger-ui"
CHECKSUM_PATH = VENDOR_PATH / "SHA256SUMS"
HTTP_METHODS = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}
EXPECTED_OPERATIONS = {
    "/accounts": {
        "get": "listAccounts",
        "post": "createAccount",
    },
    "/payments": {
        "get": "listPayments",
        "post": "createPayment",
    },
    "/internal/payment-outbox/{eventId}/recovery": {
        "post": "recoverPaymentOutboxEvent",
    },
    "/internal/payment-outbox/dead-letter-handoffs": {
        "get": "listPaymentOutboxDeadLetterHandoffs",
    },
    "/transactions": {
        "get": "listTransactions",
    },
    "/transactions/account/{id}": {
        "get": "listTransactionsByAccount",
    },
}
EXPECTED_SERVERS = {
    "/accounts": "http://localhost:8081",
    "/payments": "http://localhost:8082",
    "/internal/payment-outbox/{eventId}/recovery": "https://localhost:8082",
    "/internal/payment-outbox/dead-letter-handoffs": "https://localhost:8082",
    "/transactions": "http://localhost:8083",
    "/transactions/account/{id}": "http://localhost:8083",
}
EXPECTED_OPERATOR_SECURITY = {
    "/internal/payment-outbox/{eventId}/recovery": [
        {"recoveryOperator": []},
        {"operatorBearer": []},
    ],
    "/internal/payment-outbox/dead-letter-handoffs": [
        {"handoffInspectionOperator": []},
        {"operatorBearer": []},
    ],
}
EXPECTED_SECURITY_SCHEMES = {
    "recoveryOperator": {"type": "http", "scheme": "basic"},
    "handoffInspectionOperator": {"type": "http", "scheme": "basic"},
    "operatorBearer": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"},
}
PUBLIC_PATHS = {
    "/accounts",
    "/payments",
    "/transactions",
    "/transactions/account/{id}",
}
PUBLIC_SCHEMAS = {
    "Account",
    "CreateAccountRequest",
    "Payment",
    "CreatePaymentRequest",
    "Transaction",
}
VENDOR_FILES = {
    "LICENSE.txt",
    "NOTICE.txt",
    "swagger-ui-bundle.js",
    "swagger-ui.css",
}


def on_config(config: Any) -> Any:
    vendor_hashes = _read_vendor_hashes()
    _validate_vendor_assets(VENDOR_PATH, vendor_hashes)
    specification, base_uri = read_from_filename(str(SPEC_PATH))
    validate(specification, base_uri=base_uri)
    _validate_contract_invariants(specification)
    return config


def on_post_build(config: Any) -> None:
    site_dir = Path(config["site_dir"])
    specification, _ = read_from_filename(str(SPEC_PATH))
    public_specification = _create_public_specification(specification)
    validate(public_specification)

    public_spec_path = site_dir / "api" / "openapi-public.yaml"
    public_spec_path.parent.mkdir(parents=True, exist_ok=True)
    public_spec_path.write_text(
        yaml.safe_dump(public_specification, sort_keys=False, allow_unicode=True),
        encoding="utf-8",
    )

    required_outputs = (
        site_dir / "api-explorer" / "index.html",
        site_dir / "api" / "openapi.yaml",
        public_spec_path,
        site_dir / "javascripts" / "api-explorer.js",
        site_dir / "stylesheets" / "api-explorer.css",
        site_dir / "assets" / "swagger-ui" / "swagger-ui-bundle.js",
        site_dir / "assets" / "swagger-ui" / "swagger-ui.css",
    )
    missing = [str(path.relative_to(site_dir)) for path in required_outputs if not path.is_file()]
    if missing:
        raise PluginError("API Explorer build is missing: " + ", ".join(missing))

    vendor_hashes = _read_vendor_hashes()
    _validate_vendor_assets(site_dir / "assets" / "swagger-ui", vendor_hashes)

    explorer = (site_dir / "api-explorer" / "index.html").read_text(encoding="utf-8")
    if (
        'id="swagger-ui"' not in explorer
        or 'data-spec-url="../api/openapi-public.yaml"' not in explorer
    ):
        raise PluginError("API Explorer page is not wired to the public contract projection")

    initializer = (site_dir / "javascripts" / "api-explorer.js").read_text(
        encoding="utf-8"
    )
    required_guards = (
        "supportedSubmitMethods: []",
        "tryItOutEnabled: false",
        "persistAuthorization: false",
        "validatorUrl: null",
    )
    if not all(guard in initializer for guard in required_guards):
        raise PluginError("API Explorer request and credential controls must remain disabled")
    if "unpkg.com" in initializer or "jsdelivr.net" in initializer:
        raise PluginError("API Explorer must load only locally vendored Swagger UI assets")


def _read_vendor_hashes() -> dict[str, str]:
    if not CHECKSUM_PATH.is_file():
        raise PluginError("Vendored Swagger UI checksum manifest is missing")
    hashes: dict[str, str] = {}
    for line in CHECKSUM_PATH.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        parts = line.split(None, 1)
        if len(parts) != 2 or len(parts[0]) != 64:
            raise PluginError("Vendored Swagger UI checksum manifest is invalid")
        digest, name = parts
        if name in hashes:
            raise PluginError(f"Duplicate vendored Swagger UI checksum: {name}")
        hashes[name] = digest.lower()
    if set(hashes) != VENDOR_FILES:
        raise PluginError("Vendored Swagger UI checksum inventory changed unexpectedly")
    return hashes


def _validate_vendor_assets(directory: Path, expected_hashes: dict[str, str]) -> None:
    for name, expected_hash in expected_hashes.items():
        path = directory / name
        if not path.is_file():
            raise PluginError(f"Vendored Swagger UI asset is missing: {name}")
        actual_hash = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual_hash != expected_hash:
            raise PluginError(f"Vendored Swagger UI asset checksum changed: {name}")


def _validate_contract_invariants(specification: dict[str, Any]) -> None:
    if specification.get("openapi") != "3.0.3":
        raise PluginError("The canonical contract must remain OpenAPI 3.0.3")
    info = specification.get("info", {})
    if info.get("title") != "FinBank Microservices API" or info.get("version") != "0.6.0":
        raise PluginError("The canonical API title or version changed unexpectedly")

    paths = specification.get("paths", {})
    if set(paths) != set(EXPECTED_OPERATIONS):
        raise PluginError("The API path inventory changed without updating the docs gate")

    operation_ids: set[str] = set()
    for path, path_item in paths.items():
        actual_operations = {
            method.lower(): operation.get("operationId")
            for method, operation in path_item.items()
            if method.lower() in HTTP_METHODS
        }
        if actual_operations != EXPECTED_OPERATIONS[path]:
            raise PluginError(f"The method or operationId inventory changed for {path}")

        expected_server = EXPECTED_SERVERS[path]
        for method in actual_operations:
            operation = path_item[method]
            operation_id = operation["operationId"]
            if operation_id in operation_ids:
                raise PluginError("Every API operation must have a unique operationId")
            operation_ids.add(operation_id)

            servers = operation.get("servers", path_item.get("servers", []))
            server_urls = [server.get("url") for server in servers]
            if server_urls != [expected_server]:
                raise PluginError(
                    f"{method.upper()} {path} must use only {expected_server}"
                )

            expected_security = EXPECTED_OPERATOR_SECURITY.get(path)
            if expected_security is not None and operation.get("security") != expected_security:
                raise PluginError(
                    f"{method.upper()} {path} operator security alternatives changed"
                )

    security_schemes = specification.get("components", {}).get("securitySchemes", {})
    if set(security_schemes) != set(EXPECTED_SECURITY_SCHEMES):
        raise PluginError("The operator security-scheme inventory changed")
    for name, expected in EXPECTED_SECURITY_SCHEMES.items():
        actual = security_schemes[name]
        if any(actual.get(field) != value for field, value in expected.items()):
            raise PluginError(f"The {name} security scheme changed meaning")


def _create_public_specification(specification: dict[str, Any]) -> dict[str, Any]:
    public_specification = copy.deepcopy(specification)
    public_specification["info"]["title"] = "FinBank Public APIs"
    public_specification["info"]["description"] = (
        "Read-only public API projection generated from the canonical FinBank contract. "
        "Internal operator routes and authentication schemes remain in the raw contract only."
    )
    public_specification["paths"] = {
        path: path_item
        for path, path_item in public_specification["paths"].items()
        if path in PUBLIC_PATHS
    }
    public_specification["tags"] = [
        tag for tag in public_specification.get("tags", []) if tag.get("name") != "Payment Operations"
    ]
    components = public_specification["components"]
    components.pop("securitySchemes", None)
    public_specification.pop("security", None)
    components["schemas"] = {
        name: schema
        for name, schema in components.get("schemas", {}).items()
        if name in PUBLIC_SCHEMAS
    }

    if set(public_specification["paths"]) != PUBLIC_PATHS:
        raise PluginError("Public API projection path inventory changed")
    if "securitySchemes" in components:
        raise PluginError("Public API projection must not contain security schemes")
    if "security" in public_specification:
        raise PluginError("Public API projection must not contain global security")
    for path_item in public_specification["paths"].values():
        for method, operation in path_item.items():
            if method.lower() in HTTP_METHODS and "security" in operation:
                raise PluginError("Public API projection must not contain operation security")
    return public_specification
