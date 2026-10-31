"""Resolve the configured substrate for a full smoke; never guess global names.

Only sanitized routing enters receipts. Credentials are passed to the child via
its environment, not persisted in deployment/correctness JSON.
"""

import json
import re

from plugin_preflight import require, sha256


def resolve_substrate(deployment, compose_file, docker, inspect, running=False):
    config = json.loads(docker("compose", "-f", compose_file, "config", "--format", "json"))
    doris = json.loads(docker("compose", "-f", deployment.compose, "config", "--format", "json"))
    require(sha256(json.dumps(doris, sort_keys=True, separators=(",", ":")).encode()) ==
            deployment.target["compose_sha256"], "Doris routing changed after preflight")
    project = config["name"]
    services = config["services"]
    targets, images = {}, {}
    for service in ("postgres", "minio"):
        selected = services[service]
        name = selected.get("container_name")
        require(isinstance(name, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", name) and selected.get("image"),
                "Expected explicit substrate container names/images")
        image = json.loads(docker("image", "inspect", selected["image"]))[0]["Id"]
        ids = docker("container", "ls", "--all", "--filter", f"name=^/{name}$", "--format", "{{.ID}}").splitlines()
        require(len(ids) <= 1 and (ids or not running), "Missing or ambiguous substrate container")
        if ids:
            instance = inspect(ids[0])
            labels = instance["Config"].get("Labels") or {}
            require(instance["Name"].removeprefix("/") == name and
                    labels.get("com.docker.compose.project") == project and
                    labels.get("com.docker.compose.service") == service, "Foreign substrate container ownership")
            require(instance["Image"] == image, "Substrate image differs from selected image; explicit migration required")
            require(not running or instance["State"]["Running"], "Selected substrate is not running")
        targets[service], images[service] = name, image

    def network_names(full, service):
        keys = set(service.get("networks") or {"default": None})
        return {full["networks"][key]["name"] for key in keys}

    common = network_names(config, services["postgres"]) & network_names(config, services["minio"])
    common &= network_names(doris, doris["services"]["doris-fe"]) & network_names(doris, doris["services"]["doris-be"])
    require(len(common) == 1, "Expected one shared FE/BE/substrate network")
    network = common.pop()
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", network), "Unsupported substrate network name")
    pg = services["postgres"].get("environment") or {}
    s3 = services["minio"].get("environment") or {}
    credentials = {"SMOKE_PG_USER": pg.get("POSTGRES_USER"), "SMOKE_PG_PASSWORD": pg.get("POSTGRES_PASSWORD"),
                   "SMOKE_S3_KEY_ID": s3.get("MINIO_ROOT_USER"), "SMOKE_S3_SECRET": s3.get("MINIO_ROOT_PASSWORD")}
    require(all(isinstance(v, str) and v and "\x00" not in v for v in credentials.values()),
            "Full smoke requires explicit substrate credentials")
    python = json.loads(docker("image", "inspect", "python:3.12-slim"))[0]["Id"]
    mc = json.loads(docker("image", "inspect", "minio/mc:latest"))[0]["Id"]
    public = {"project": project, "postgres": targets["postgres"], "minio": targets["minio"], "network": network,
              "images": {**images, "python": python, "mc": mc},
              "compose_sha256": sha256(json.dumps(config, sort_keys=True, separators=(",", ":")).encode())}
    private = {**credentials, "SMOKE_PG_CONTAINER": targets["postgres"], "SMOKE_MINIO_CONTAINER": targets["minio"],
               "SMOKE_SUBSTRATE_NETWORK": network, "SMOKE_PYTHON_IMAGE": python, "SMOKE_MC_IMAGE": mc}
    return public, private
