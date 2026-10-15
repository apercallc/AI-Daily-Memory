"""Conservative compatibility checks for versioned OpenAPI contracts."""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import cast

JsonObject = Mapping[str, object]


def find_breaking_changes(baseline: JsonObject, current: JsonObject) -> list[str]:
    """Return incompatible changes from a released contract to a candidate contract."""
    changes: list[str] = []
    old_paths = _object(baseline.get("paths"))
    new_paths = _object(current.get("paths"))

    for path, old_path_item in old_paths.items():
        context = str(path)
        new_path_item = _object(new_paths.get(path))
        if path not in new_paths:
            changes.append(f"{context}: path was removed")
            continue

        _compare_parameters(
            _sequence(_object(old_path_item).get("parameters")),
            _sequence(new_path_item.get("parameters")),
            context,
            baseline,
            current,
            changes,
        )
        for method, old_operation in _object(old_path_item).items():
            if method in {"parameters", "servers", "summary", "description", "$ref"}:
                continue
            if not isinstance(old_operation, Mapping):
                continue
            new_operation = _object(new_path_item.get(method))
            operation_context = f"{str(method).upper()} {context}"
            if method not in new_path_item:
                changes.append(f"{operation_context}: operation was removed")
                continue
            _compare_operation(
                _object(old_operation),
                new_operation,
                operation_context,
                baseline,
                current,
                changes,
            )

    old_security = baseline.get("security")
    new_security = current.get("security")
    if old_security != new_security:
        changes.append("API-wide security requirements changed; review authorization compatibility")
    return changes


def _compare_operation(
    old: JsonObject,
    new: JsonObject,
    context: str,
    old_spec: JsonObject,
    new_spec: JsonObject,
    changes: list[str],
) -> None:
    if old.get("operationId") != new.get("operationId"):
        changes.append(f"{context}: operationId changed")

    if old.get("security") != new.get("security"):
        changes.append(f"{context}: security requirements changed")

    _compare_parameters(
        _sequence(old.get("parameters")),
        _sequence(new.get("parameters")),
        context,
        old_spec,
        new_spec,
        changes,
    )
    _compare_request_body(
        _object(old.get("requestBody")),
        _object(new.get("requestBody")),
        context,
        old_spec,
        new_spec,
        changes,
    )
    _compare_responses(
        _object(old.get("responses")),
        _object(new.get("responses")),
        context,
        old_spec,
        new_spec,
        changes,
    )


def _compare_parameters(
    old_values: Sequence[object],
    new_values: Sequence[object],
    context: str,
    old_spec: JsonObject,
    new_spec: JsonObject,
    changes: list[str],
) -> None:
    old_params = {_parameter_key(item): _object(item) for item in old_values}
    new_params = {_parameter_key(item): _object(item) for item in new_values}
    old_params.pop(("", ""), None)
    new_params.pop(("", ""), None)

    for key, old_param in old_params.items():
        label = f"{key[0]} parameter '{key[1]}'"
        new_param = new_params.get(key)
        if new_param is None:
            changes.append(f"{context}: {label} was removed")
            continue
        if old_param.get("required") is not True and new_param.get("required") is True:
            changes.append(f"{context}: {label} became required")
        old_schema = _object(old_param.get("schema"))
        new_schema = _object(new_param.get("schema"))
        if old_schema and new_schema:
            _compare_schema(
                old_schema,
                new_schema,
                f"{context} {label}",
                "request",
                old_spec,
                new_spec,
                changes,
                set(),
            )

    for key, new_param in new_params.items():
        if key not in old_params and new_param.get("required") is True:
            changes.append(f"{context}: new required parameter '{key[1]}' was added")


def _parameter_key(value: object) -> tuple[str, str]:
    param = _object(value)
    location = param.get("in")
    name = param.get("name")
    return (location if isinstance(location, str) else "", name if isinstance(name, str) else "")


def _compare_request_body(
    old: JsonObject,
    new: JsonObject,
    context: str,
    old_spec: JsonObject,
    new_spec: JsonObject,
    changes: list[str],
) -> None:
    if old and not new:
        changes.append(f"{context}: request body was removed")
        return
    if not old:
        if new.get("required") is True:
            changes.append(f"{context}: a required request body was added")
        return
    if old.get("required") is not True and new.get("required") is True:
        changes.append(f"{context}: request body became required")
    _compare_content(
        _object(old.get("content")),
        _object(new.get("content")),
        context,
        "request",
        old_spec,
        new_spec,
        changes,
    )


def _compare_responses(
    old: JsonObject,
    new: JsonObject,
    context: str,
    old_spec: JsonObject,
    new_spec: JsonObject,
    changes: list[str],
) -> None:
    for status, old_response in old.items():
        new_response = _object(new.get(status))
        response_context = f"{context} response {status}"
        if status not in new:
            changes.append(f"{response_context} was removed")
            continue
        _compare_content(
            _object(_object(old_response).get("content")),
            _object(new_response.get("content")),
            response_context,
            "response",
            old_spec,
            new_spec,
            changes,
        )


def _compare_content(
    old: JsonObject,
    new: JsonObject,
    context: str,
    direction: str,
    old_spec: JsonObject,
    new_spec: JsonObject,
    changes: list[str],
) -> None:
    for media_type, old_media in old.items():
        new_media = _object(new.get(media_type))
        media_context = f"{context} {media_type}"
        if media_type not in new:
            changes.append(f"{media_context}: content type was removed")
            continue
        old_schema = _object(_object(old_media).get("schema"))
        new_schema = _object(new_media.get("schema"))
        if old_schema and new_schema:
            _compare_schema(
                old_schema,
                new_schema,
                media_context,
                direction,
                old_spec,
                new_spec,
                changes,
                set(),
            )


def _compare_schema(
    old_schema: JsonObject,
    new_schema: JsonObject,
    context: str,
    direction: str,
    old_spec: JsonObject,
    new_spec: JsonObject,
    changes: list[str],
    seen_refs: set[tuple[str, str]],
) -> None:
    old_ref = old_schema.get("$ref")
    new_ref = new_schema.get("$ref")
    ref_pair = (
        old_ref if isinstance(old_ref, str) else "",
        new_ref if isinstance(new_ref, str) else "",
    )
    if ref_pair != ("", "") and ref_pair in seen_refs:
        return
    next_seen = seen_refs | {ref_pair} if ref_pair != ("", "") else seen_refs
    old = _resolve_schema(old_schema, old_spec)
    new = _resolve_schema(new_schema, new_spec)

    old_types = _type_set(old.get("type"))
    new_types = _type_set(new.get("type"))
    if old_types and new_types:
        incompatible_type = (
            not old_types.issubset(new_types)
            if direction == "request"
            else not new_types.issubset(old_types)
        )
        if incompatible_type:
            changes.append(
                f"{context}: schema type changed from {sorted(old_types)} to {sorted(new_types)}",
            )

    _compare_enum(old, new, context, direction, changes)
    _compare_schema_constraints(old, new, context, direction, changes)

    old_required = _string_set(old.get("required"))
    new_required = _string_set(new.get("required"))
    if direction == "request":
        changes.extend(
            f"{context}: request property '{name}' became required"
            for name in sorted(new_required - old_required)
        )
    else:
        changes.extend(
            f"{context}: response property '{name}' is no longer guaranteed"
            for name in sorted(old_required - new_required)
        )

    old_properties = _object(old.get("properties"))
    new_properties = _object(new.get("properties"))
    for name, old_property in old_properties.items():
        new_property = _object(new_properties.get(name))
        property_context = f"{context} property '{name}'"
        if name not in new_properties:
            changes.append(f"{property_context} was removed")
            continue
        _compare_schema(
            _object(old_property),
            new_property,
            property_context,
            direction,
            old_spec,
            new_spec,
            changes,
            next_seen,
        )

    for keyword in ("items", "additionalProperties"):
        old_value = old.get(keyword)
        new_value = new.get(keyword)
        if isinstance(old_value, Mapping) and isinstance(new_value, Mapping):
            _compare_schema(
                cast(JsonObject, old_value),
                cast(JsonObject, new_value),
                f"{context} {keyword}",
                direction,
                old_spec,
                new_spec,
                changes,
                next_seen,
            )
        elif keyword == "additionalProperties":
            if direction == "request" and old_value is not False and new_value is False:
                changes.append(f"{context}: additional properties are no longer accepted")
            if direction == "response" and old_value is False and new_value is not False:
                changes.append(f"{context}: response may contain new additional properties")

    for keyword in ("allOf", "anyOf", "oneOf"):
        old_parts = _sequence(old.get(keyword))
        new_parts = _sequence(new.get(keyword))
        if not old_parts and not new_parts:
            continue
        if len(old_parts) != len(new_parts):
            changes.append(f"{context}: {keyword} schema composition changed")
            continue
        for index, (old_part, new_part) in enumerate(zip(old_parts, new_parts, strict=True)):
            _compare_schema(
                _object(old_part),
                _object(new_part),
                f"{context} {keyword}[{index}]",
                direction,
                old_spec,
                new_spec,
                changes,
                next_seen,
            )


def _compare_enum(
    old: JsonObject,
    new: JsonObject,
    context: str,
    direction: str,
    changes: list[str],
) -> None:
    old_enum = old.get("enum")
    new_enum = new.get("enum")
    old_values = _sequence(old_enum)
    new_values = _sequence(new_enum)
    if not old_values:
        return
    if not new_values:
        if direction == "response":
            changes.append(f"{context}: response enum restriction was removed")
        return
    removed = [value for value in old_values if value not in new_values]
    added = [value for value in new_values if value not in old_values]
    incompatible = removed if direction == "request" else added
    if incompatible:
        changes.append(f"{context}: enum values changed incompatibly: {incompatible!r}")


def _compare_schema_constraints(
    old: JsonObject,
    new: JsonObject,
    context: str,
    direction: str,
    changes: list[str],
) -> None:
    constraints = (
        ("minimum", "maximum"),
        ("minLength", "maxLength"),
        ("minItems", "maxItems"),
        ("minProperties", "maxProperties"),
    )
    for minimum, maximum in constraints:
        old_min = old.get(minimum)
        new_min = new.get(minimum)
        old_max = old.get(maximum)
        new_max = new.get(maximum)
        narrowed = (
            _greater(new_min, old_min) or _less(new_max, old_max)
            if direction == "request"
            else _less(new_min, old_min) or _greater(new_max, old_max)
        )
        if narrowed:
            changes.append(f"{context}: {minimum}/{maximum} constraints narrowed incompatibly")

    changes.extend(
        f"{context}: {keyword} constraint changed"
        for keyword in ("format", "pattern")
        if old.get(keyword) != new.get(keyword) and keyword in old
    )


def _greater(candidate: object, baseline: object) -> bool:
    return (
        isinstance(candidate, (int, float))
        and isinstance(baseline, (int, float))
        and candidate > baseline
    )


def _less(candidate: object, baseline: object) -> bool:
    return (
        isinstance(candidate, (int, float))
        and isinstance(baseline, (int, float))
        and candidate < baseline
    )


def _resolve_schema(schema: JsonObject, spec: JsonObject) -> dict[str, object]:
    resolved = dict(schema)
    reference = resolved.pop("$ref", None)
    if not isinstance(reference, str) or not reference.startswith("#/"):
        return resolved
    target: object = spec
    for part in reference[2:].split("/"):
        decoded = part.replace("~1", "/").replace("~0", "~")
        target = _object(target).get(decoded)
    merged = dict(_object(target))
    merged.update(resolved)
    return merged


def _object(value: object) -> dict[str, object]:
    if isinstance(value, Mapping) and all(isinstance(key, str) for key in value):
        return cast(dict[str, object], value)
    return {}


def _sequence(value: object) -> Sequence[object]:
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        return value
    return ()


def _string_set(value: object) -> set[str]:
    return {item for item in _sequence(value) if isinstance(item, str)}


def _type_set(value: object) -> set[str]:
    if isinstance(value, str):
        return {value}
    return _string_set(value)


def _load_spec(path: Path) -> dict[str, object]:
    with path.open(encoding="utf-8") as file:
        loaded = json.load(file)
    if not isinstance(loaded, dict):
        raise ValueError(f"OpenAPI document must be an object: {path}")
    return cast(dict[str, object], loaded)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Check OpenAPI backward compatibility")
    parser.add_argument("baseline", type=Path)
    parser.add_argument("current", type=Path)
    args = parser.parse_args(argv)
    try:
        changes = find_breaking_changes(_load_spec(args.baseline), _load_spec(args.current))
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"OpenAPI compatibility check failed: {error}", file=sys.stderr)
        return 2
    if changes:
        for change in changes:
            print(f"BREAKING: {change}")
        return 1
    print("OpenAPI contract is backward-compatible with the baseline.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
