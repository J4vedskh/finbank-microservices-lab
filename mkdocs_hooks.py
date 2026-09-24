from __future__ import annotations

import copy
import hashlib
from pathlib import Path
from typing import Any

import yaml
from mkdocs.exceptions import PluginError
from openapi_schema_validator import OAS30Validator
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
SENSITIVE_EXAMPLE_KEYS = {
    "actor",
    "authorization",
    "authorities",
    "authority",
    "basic",
    "bearer",
    "credential",
    "credentials",
    "eventkey",
    "eventpayload",
    "headers",
    "idempotencykey",
    "idempotencykeyhash",
    "operator",
    "password",
    "payload",
    "principal",
    "reason",
    "recoverykey",
    "role",
    "roles",
    "scope",
    "scopes",
    "secret",
    "token",
    "topic",
    "username",
}


def on_config(config: Any) -> Any:
    vendor_hashes = _read_vendor_hashes()
    _validate_vendor_assets(VENDOR_PATH, vendor_hashes)
    specification, base_uri = read_from_filename(str(SPEC_PATH))
    validate(specification, base_uri=base_uri)
    _validate_contract_invariants(specification)
    _validate_response_examples(specification, required_paths=PUBLIC_PATHS)
    public_specification = _create_public_specification(specification)
    validate(public_specification)
    _validate_public_projection(specification, public_specification)
    _validate_response_examples(public_specification, required_paths=PUBLIC_PATHS)
    return config


def on_post_build(config: Any) -> None:
    site_dir = Path(config["site_dir"])
    specification, _ = read_from_filename(str(SPEC_PATH))
    public_specification = _create_public_specification(specification)
    validate(public_specification)
    _validate_public_projection(specification, public_specification)
    _validate_response_examples(public_specification, required_paths=PUBLIC_PATHS)

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
    if info.get("title") != "FinBank Microservices API" or info.get("version") != "0.7.0":
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


def _validate_response_examples(
        specification: dict[str, Any],
        required_paths: set[str]
) -> None:
    validator = OAS30Validator(
        specification,
        format_checker=OAS30Validator.FORMAT_CHECKER,
    )
    for path, path_item in specification.get("paths", {}).items():
        for method, operation in path_item.items():
            if method.lower() not in HTTP_METHODS:
                continue
            for status, response in operation.get("responses", {}).items():
                response_has_json_example = False
                response_has_json_representation = False
                for media_type, media in response.get("content", {}).items():
                    examples = _local_examples(media, method, path, status, media_type)
                    schema = media.get("schema")
                    if examples and schema is None:
                        raise PluginError(
                            f"{method.upper()} {path} {status} examples require a schema"
                        )
                    for name, value in examples:
                        errors = list(validator.evolve(schema=schema).iter_errors(value))
                        if errors:
                            raise PluginError(
                                f"{method.upper()} {path} {status} example {name} is invalid: "
                                f"{errors[0].message}"
                            )
                        _validate_example_privacy(value, method, path, status, name)
                    if _is_json_media_type(media_type):
                        response_has_json_representation = True
                        if examples:
                            response_has_json_example = True
                        if path in required_paths and str(status).startswith("2"):
                            _validate_public_example_set(
                                media,
                                schema,
                                method,
                                path,
                                status,
                                media_type,
                            )

                if (
                    path in required_paths
                    and str(status).startswith("2")
                    and response_has_json_representation
                    and not response_has_json_example
                ):
                    raise PluginError(
                        f"{method.upper()} {path} {status} requires a local JSON response example"
                    )


def _is_json_media_type(media_type: str) -> bool:
    return media_type == "application/json" or (
        media_type.startswith("application/") and media_type.endswith("+json")
    )


def _validate_public_example_set(
        media: dict[str, Any],
        schema: dict[str, Any] | None,
        method: str,
        path: str,
        status: str,
        media_type: str
) -> None:
    named_examples = media.get("examples")
    if not isinstance(named_examples, dict) or not named_examples:
        raise PluginError(
            f"{method.upper()} {path} {status} {media_type} requires named examples"
        )
    values = [example.get("value") for example in named_examples.values()]
    if schema is not None and schema.get("type") == "array":
        if not any(value == [] for value in values):
            raise PluginError(
                f"{method.upper()} {path} {status} list examples require an empty result"
            )
        if not any(isinstance(value, list) and len(value) > 0 for value in values):
            raise PluginError(
                f"{method.upper()} {path} {status} list examples require a populated result"
            )


def _local_examples(
        media: dict[str, Any],
        method: str,
        path: str,
        status: str,
        media_type: str
) -> list[tuple[str, Any]]:
    if "example" in media and "examples" in media:
        raise PluginError(
            f"{method.upper()} {path} {status} {media_type} cannot define both example and examples"
        )
    if "example" in media:
        return [("example", media["example"])]

    local_examples: list[tuple[str, Any]] = []
    for name, example in media.get("examples", {}).items():
        if "externalValue" in example or "value" not in example:
            raise PluginError(
                f"{method.upper()} {path} {status} example {name} must contain a local value"
            )
        local_examples.append((name, example["value"]))
    return local_examples


def _validate_example_privacy(
        value: Any,
        method: str,
        path: str,
        status: str,
        name: str
) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            normalized_key = key.replace("-", "").replace("_", "").lower()
            if normalized_key in SENSITIVE_EXAMPLE_KEYS:
                raise PluginError(
                    f"{method.upper()} {path} {status} example {name} exposes {key}"
                )
            if normalized_key == "customername" and (
                not isinstance(child, str) or not child.startswith("Demo ")
            ):
                raise PluginError(
                    f"{method.upper()} {path} {status} example {name} must use a synthetic customerName"
                )
            _validate_example_privacy(child, method, path, status, name)
    elif isinstance(value, list):
        for child in value:
            _validate_example_privacy(child, method, path, status, name)


def _validate_public_projection(
        canonical: dict[str, Any],
        public: dict[str, Any]
) -> None:
    for path in PUBLIC_PATHS:
        for method in EXPECTED_OPERATIONS[path]:
            if (
                public["paths"][path][method].get("responses")
                != canonical["paths"][path][method].get("responses")
            ):
                raise PluginError(
                    f"Public projection changed {method.upper()} {path} responses"
                )

    referenced_schemas = _schema_references(public.get("paths", {}))
    available_schemas = set(public.get("components", {}).get("schemas", {}))
    missing_schemas = referenced_schemas - available_schemas
    if missing_schemas:
        raise PluginError(
            "Public projection is missing schemas: " + ", ".join(sorted(missing_schemas))
        )


def _schema_references(value: Any) -> set[str]:
    references: set[str] = set()
    if isinstance(value, dict):
        reference = value.get("$ref")
        prefix = "#/components/schemas/"
        if isinstance(reference, str) and reference.startswith(prefix):
            references.add(reference.removeprefix(prefix))
        for child in value.values():
            references.update(_schema_references(child))
    elif isinstance(value, list):
        for child in value:
            references.update(_schema_references(child))
    return references
