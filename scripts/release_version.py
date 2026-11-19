"""Release names, compatibility metadata and publication checks (no Android build)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

FIELDS = {"versionName", "versionCode", "legacyBridge", "legacyVersionCode"}
NAME = re.compile(r"bate([0-9]{1,6})\.([0-9]{1,6})\.([0-9]{1,6})")
APK = "app-release.apk"
LIMIT = 2_100_000_000


def parse_properties(text):
    values = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if not separator or key not in FIELDS or key in values:
            raise ValueError("Unknown or duplicate release property")
        values[key] = value
    if values.keys() != FIELDS or not NAME.fullmatch(values["versionName"]):
        raise ValueError("Version name must use bateX.Y.Z")
    if values["legacyBridge"] not in ("true", "false"):
        raise ValueError("legacyBridge must be true or false")
    for key in ("versionCode", "legacyVersionCode"):
        if not re.fullmatch(r"[1-9][0-9]{0,9}", values[key]):
            raise ValueError("Version codes must be positive integers")
        if not 1 <= int(values[key]) <= LIMIT:
            raise ValueError("Version code outside Android range")
    return values


def validate(config, latest, legacy):
    code = int(config["versionCode"])
    published = latest.get("versionCode")
    old = legacy.get("version")
    if type(published) is not int or type(old) is not int:
        raise ValueError("Published version codes must be integers")
    if not 1 <= published < code <= LIMIT or not 1 <= old <= published:
        raise ValueError("Pending code must be higher than published codes")
    if latest.get("asset") != APK or not isinstance(latest.get("versionName"), str):
        raise ValueError("Invalid published metadata")
    tag = latest.get("tag")
    if tag != "build-" + str(published) and tag != latest.get("versionName"):
        raise ValueError("Published tag does not match metadata")
    if old != int(config["legacyVersionCode"]):
        raise ValueError("Legacy bridge manifest changed")
    if config["legacyBridge"] == "true" and tag != "build-" + str(published):
        raise ValueError("Bridge may only follow the legacy release protocol")


def metadata(config):
    return {"versionCode": int(config["versionCode"]), "versionName": config["versionName"],
            "tag": config["versionName"], "asset": APK}


def next_properties(config):
    match = NAME.fullmatch(config["versionName"])
    if match is None:
        raise ValueError("Invalid version name")
    parts = [int(part) for part in match.groups()]
    parts[2] += 1
    if parts[2] > 999999 or int(config["versionCode"]) >= LIMIT:
        raise ValueError("Version exhausted; select a new version explicitly")
    result = dict(config)
    result["versionName"] = "bate" + ".".join(map(str, parts))
    result["versionCode"] = str(int(config["versionCode"]) + 1)
    result["legacyBridge"] = "false"
    if config["legacyBridge"] == "true":
        result["legacyVersionCode"] = config["versionCode"]
    return result


def encode_properties(config):
    return "".join(key + "=" + config[key] + "\n" for key in
                   ("versionName", "versionCode", "legacyBridge", "legacyVersionCode"))


def tags(config):
    result = [config["versionName"]]
    if config["legacyBridge"] == "true":
        result.append("build-" + config["versionCode"])
    return result


def check_tag_conflicts(config, existing):
    if set(tags(config)) & set(existing):
        raise ValueError("Release tag already exists; do not overwrite a published version")


def run(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


def repository():
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("Invalid GitHub repository")
    return repo


def config_at(root):
    return parse_properties((root / "release.properties").read_text(encoding="utf-8"))


def validate_current(root):
    local = config_at(root)
    sha = os.environ.get("GITHUB_SHA", "")
    if not re.fullmatch(r"[0-9a-f]{40,64}", sha):
        raise ValueError("Invalid build commit")
    run("git", "merge-base", "--is-ancestor", sha, "origin/main")
    remote = parse_properties(run("git", "show", "origin/main:release.properties"))
    if remote != local:
        raise ValueError("Pending release configuration changed during the build")
    changed = run("git", "diff", "--name-only", sha, "origin/main", "--").splitlines()
    for path in changed:
        if not (path.endswith(".md") or path in ("LICENSE", "NOTICE", "COPYING") or path.startswith("LICENSES/")):
            raise ValueError("Non-documentation changes arrived during release: " + path)
    latest = json.loads(run("git", "show", "origin/main:updates/latest.json"))
    old = json.loads(run("git", "show", "origin/main:version.json"))
    validate(remote, latest, old)
    return local


def check_remote_tags(config):
    repo = repository()
    for tag in tags(config):
        for endpoint in (f"repos/{repo}/git/ref/tags/{tag}", f"repos/{repo}/releases/tags/{tag}"):
            response = subprocess.run(["gh", "api", "--include", endpoint], capture_output=True, text=True)
            if response.returncode == 0:
                check_tag_conflicts(config, [tag])
            if "HTTP 404" not in response.stderr and not re.search(r"HTTP/[^\s]+ 404", response.stdout):
                raise RuntimeError("Could not establish tag absence: " + response.stderr.strip())


def digest(path):
    hash_value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hash_value.update(block)
    return "sha256:" + hash_value.hexdigest()


def verify_assets(root, apk):
    config = config_at(root)
    repo = repository()
    expected_hash = digest(apk)
    for tag in tags(config):
        release = json.loads(run("gh", "api", f"repos/{repo}/releases/tags/{tag}"))
        assets = [asset for asset in release.get("assets", []) if asset.get("name") == APK]
        if len(assets) != 1 or assets[0].get("size") != apk.stat().st_size:
            raise ValueError("Published APK is missing or differs in size")
        remote_digest = assets[0].get("digest")
        if remote_digest:
            if remote_digest != expected_hash:
                raise ValueError("Published APK digest differs")
        else:
            # Older API responses may omit digest. Verify actual bytes rather than guess.
            with tempfile.TemporaryDirectory() as directory:
                run("gh", "release", "download", tag, "--repo", repo, "--pattern", APK, "--dir", directory)
                if digest(Path(directory) / APK) != expected_hash:
                    raise ValueError("Published APK bytes differ")
    return config


def advance(root):
    config = config_at(root)
    previous = json.loads((root / "updates/latest.json").read_text(encoding="utf-8"))
    old = json.loads((root / "version.json").read_text(encoding="utf-8"))
    validate(config, previous, old)
    following = next_properties(config)
    (root / "updates/latest.json").write_text(json.dumps(metadata(config), indent=2) + "\n", encoding="utf-8")
    if config["legacyBridge"] == "true":
        (root / "version.json").write_text(json.dumps({"version": int(config["versionCode"])}) + "\n", encoding="utf-8")
    (root / "release.properties").write_text(encode_properties(following), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("inspect", "validate-current", "check-tags", "verify-assets", "advance"))
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--output", type=Path)
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()
    config = config_at(args.root)
    if args.command == "inspect":
        next_properties(config)  # 推进不可行时在发布任何制品之前失败。
        validate(config, json.loads((args.root / "updates/latest.json").read_text(encoding="utf-8")),
                 json.loads((args.root / "version.json").read_text(encoding="utf-8")))
        output = "".join(f"{key}={config[key]}\n" for key in
                         ("versionName", "versionCode", "legacyBridge", "legacyVersionCode"))
        if args.output:
            with args.output.open("a", encoding="utf-8") as stream:
                stream.write(output)
        print(output, end="")
    elif args.command == "validate-current":
        validate_current(args.root)
    elif args.command == "check-tags":
        check_remote_tags(config)
    elif args.command == "verify-assets":
        if args.apk is None:
            parser.error("--apk is required")
        verify_assets(args.root, args.apk)
    else:
        advance(args.root)


if __name__ == "__main__":
    main()
