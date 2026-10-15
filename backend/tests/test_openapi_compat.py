from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from app.openapi_compat import find_breaking_changes, main


def _spec(operation: dict[str, Any]) -> dict[str, Any]:
    return {
        "openapi": "3.1.0",
        "info": {"title": "test", "version": "1.0.0"},
        "paths": {"/v1/items": {"get": operation}},
        "components": {"schemas": {}},
    }


def _get_operation(
    *,
    response_schema: dict[str, Any] | None = None,
    request_schema: dict[str, Any] | None = None,
) -> dict[str, Any]:
    operation: dict[str, Any] = {
        "operationId": "get_items",
        "responses": {
            "200": {
                "description": "OK",
                "content": {
                    "application/json": {
                        "schema": response_schema or {"type": "array", "items": {"type": "string"}},
                    },
                },
            },
        },
    }
    if request_schema is not None:
        operation["requestBody"] = {
            "required": True,
            "content": {"application/json": {"schema": request_schema}},
        }
    return operation


def test_additive_optional_response_field_is_compatible() -> None:
    baseline = _spec(
        _get_operation(
            response_schema={"type": "object", "properties": {"id": {"type": "string"}}},
        ),
    )
    current = _spec(
        _get_operation(
            response_schema={
                "type": "object",
                "properties": {"id": {"type": "string"}, "label": {"type": "string"}},
            },
        ),
    )

    assert find_breaking_changes(baseline, current) == []


def test_removed_route_is_breaking() -> None:
    baseline = _spec(_get_operation())
    current = {**baseline, "paths": {}}

    changes = find_breaking_changes(baseline, current)

    assert any("/v1/items" in change and "removed" in change for change in changes)


def test_new_required_request_field_is_breaking() -> None:
    request = {"type": "object", "properties": {"name": {"type": "string"}}}
    baseline = _spec(_get_operation(request_schema=request))
    current = _spec(
        _get_operation(
            request_schema={
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "owner": {"type": "string"},
                },
                "required": ["owner"],
            },
        ),
    )

    changes = find_breaking_changes(baseline, current)

    assert any("owner" in change and "required" in change for change in changes)


def test_response_field_type_change_is_breaking_through_refs() -> None:
    baseline = _spec(
        _get_operation(response_schema={"$ref": "#/components/schemas/Item"}),
    )
    baseline["components"]["schemas"]["Item"] = {
        "type": "object",
        "properties": {"id": {"type": "string"}},
    }
    current = _spec(
        _get_operation(response_schema={"$ref": "#/components/schemas/Item"}),
    )
    current["components"]["schemas"]["Item"] = {
        "type": "object",
        "properties": {"id": {"type": "integer"}},
    }

    changes = find_breaking_changes(baseline, current)

    assert any("id" in change and "type" in change for change in changes)


def test_removing_an_allowed_request_enum_value_is_breaking() -> None:
    baseline = _spec(
        _get_operation(
            request_schema={
                "type": "object",
                "properties": {"kind": {"type": "string", "enum": ["note", "task"]}},
            },
        ),
    )
    current = _spec(
        _get_operation(
            request_schema={
                "type": "object",
                "properties": {"kind": {"type": "string", "enum": ["note"]}},
            },
        ),
    )

    changes = find_breaking_changes(baseline, current)

    assert any("task" in change and "enum" in change for change in changes)


def test_removed_operation_and_response_code_are_breaking() -> None:
    baseline = _spec(_get_operation())
    current = _spec({"operationId": "get_items", "responses": {}})
    current["paths"]["/v1/items"].pop("get")
    without_response = _spec(_get_operation())
    without_response["paths"]["/v1/items"]["get"]["responses"].pop("200")

    assert any("operation was removed" in item for item in find_breaking_changes(baseline, current))
    assert any(
        "response 200 was removed" in item
        for item in find_breaking_changes(baseline, without_response)
    )


def test_added_required_parameter_and_narrowed_request_limit_are_breaking() -> None:
    baseline = _spec(_get_operation(request_schema={"type": "string", "maxLength": 100}))
    current = _spec(
        _get_operation(
            request_schema={"type": "string", "maxLength": 20},
        ),
    )
    current["paths"]["/v1/items"]["get"]["parameters"] = [
        {"name": "cursor", "in": "query", "required": True, "schema": {"type": "string"}},
    ]

    changes = find_breaking_changes(baseline, current)

    assert any("new required parameter 'cursor'" in item for item in changes)
    assert any("constraints narrowed" in item for item in changes)


def test_response_field_and_security_contract_changes_are_breaking() -> None:
    baseline = _spec(
        _get_operation(
            response_schema={
                "type": "object",
                "properties": {"id": {"type": "string"}, "title": {"type": "string"}},
                "required": ["id", "title"],
            },
        ),
    )
    baseline["security"] = [{"BearerAuth": []}]
    current = _spec(
        _get_operation(
            response_schema={"type": "object", "properties": {"id": {"type": "string"}}},
        ),
    )
    current["security"] = []

    changes = find_breaking_changes(baseline, current)

    assert any("title" in item and "no longer guaranteed" in item for item in changes)
    assert any("security requirements changed" in item for item in changes)


def test_cli_reports_compatible_breaking_and_invalid_specs(tmp_path: Path) -> None:
    baseline_path = tmp_path / "baseline.json"
    current_path = tmp_path / "current.json"
    baseline = _spec(_get_operation())
    baseline_path.write_text(json.dumps(baseline), encoding="utf-8")
    current_path.write_text(json.dumps(baseline), encoding="utf-8")

    assert main([str(baseline_path), str(current_path)]) == 0

    current_path.write_text(json.dumps({**baseline, "paths": {}}), encoding="utf-8")
    assert main([str(baseline_path), str(current_path)]) == 1

    current_path.write_text("invalid", encoding="utf-8")
    assert main([str(baseline_path), str(current_path)]) == 2


def test_removed_or_newly_required_parameter_is_breaking() -> None:
    baseline = _spec(_get_operation())
    baseline["paths"]["/v1/items"]["get"]["parameters"] = [
        {"name": "cursor", "in": "query", "required": False, "schema": {"type": "string"}},
    ]
    removed = _spec(_get_operation())
    required = _spec(_get_operation())
    required["paths"]["/v1/items"]["get"]["parameters"] = [
        {"name": "cursor", "in": "query", "required": True, "schema": {"type": "string"}},
    ]

    assert any(
        "parameter 'cursor' was removed" in item
        for item in find_breaking_changes(baseline, removed)
    )
    assert any(
        "parameter 'cursor' became required" in item
        for item in find_breaking_changes(baseline, required)
    )


def test_request_body_and_content_type_removals_are_breaking() -> None:
    baseline = _spec(_get_operation(request_schema={"type": "string"}))
    no_body = _spec(_get_operation())
    no_media = _spec(_get_operation(request_schema={"type": "string"}))
    no_media["paths"]["/v1/items"]["get"]["requestBody"]["content"] = {
        "application/xml": {"schema": {"type": "string"}},
    }

    assert any(
        "request body was removed" in item for item in find_breaking_changes(baseline, no_body)
    )
    assert any(
        "content type was removed" in item for item in find_breaking_changes(baseline, no_media)
    )


def test_response_enum_expansion_and_restriction_removal_are_breaking() -> None:
    baseline = _spec(_get_operation(response_schema={"type": "string", "enum": ["ready"]}))
    expanded = _spec(
        _get_operation(response_schema={"type": "string", "enum": ["ready", "new"]}),
    )
    unrestricted = _spec(_get_operation(response_schema={"type": "string"}))

    assert any(
        "new" in item and "enum" in item for item in find_breaking_changes(baseline, expanded)
    )
    assert any(
        "restriction was removed" in item for item in find_breaking_changes(baseline, unrestricted)
    )


def test_schema_type_and_additional_property_changes_follow_direction() -> None:
    request = _spec(_get_operation(request_schema={"type": "string"}))
    narrowed_request = _spec(_get_operation(request_schema={"type": "integer"}))
    response = _spec(
        _get_operation(response_schema={"type": "object", "additionalProperties": False})
    )
    expanded_response = _spec(
        _get_operation(response_schema={"type": "object", "additionalProperties": True}),
    )

    assert any(
        "schema type changed" in item for item in find_breaking_changes(request, narrowed_request)
    )
    assert any(
        "additional properties" in item
        for item in find_breaking_changes(response, expanded_response)
    )


def test_recursive_refs_and_changed_schema_composition_are_handled() -> None:
    baseline = _spec(
        _get_operation(response_schema={"$ref": "#/components/schemas/Node"}),
    )
    baseline["components"]["schemas"]["Node"] = {
        "type": "object",
        "properties": {"child": {"$ref": "#/components/schemas/Node"}},
    }
    current = _spec(
        _get_operation(
            response_schema={
                "oneOf": [{"type": "string"}, {"type": "integer"}],
            },
        ),
    )
    baseline["paths"]["/v1/items"]["get"]["responses"]["200"]["content"]["application/json"][
        "schema"
    ] = {"oneOf": [{"type": "string"}]}

    changes = find_breaking_changes(baseline, current)

    assert any("oneOf schema composition changed" in item for item in changes)
