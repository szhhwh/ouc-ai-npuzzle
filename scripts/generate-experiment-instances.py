#!/usr/bin/env python3
"""Generate reproducible, exactly depth-certified experiment instances.

Compile the project first, then run this script from the repository root.
The append-only journal permits resumption after interruption.
"""
import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
import random
import selectors
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "result" / "experiment"
CERTIFIER_CLASSES = OUT / "certifier-classes"
DEPTHS3 = (6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 30)
DEPTHS4 = (5, 8, 10, 12, 15, 18, 20, 22, 25, 30, 35, 40)
COUNT = 30
VERSION = 1


def encode(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def atomic_write(path, data):
    tmp = path.with_name(path.name + ".tmp")
    with tmp.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(data)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(tmp, path)


def neighbors(size):
    return [tuple(j for j in range(size * size)
                  if abs(i // size - j // size) + abs(i % size - j % size) == 1)
            for i in range(size * size)]


def swap(board, a, b):
    cells = list(board)
    cells[a], cells[b] = cells[b], cells[a]
    return tuple(cells)


def three_samples(seed):
    goal = tuple(range(1, 9)) + (0,)
    adj = neighbors(3)
    distance = {goal: 0}
    queue = collections.deque([(goal, 8)])
    layers = {depth: [] for depth in DEPTHS3}
    while queue:
        board, blank = queue.popleft()
        depth = distance[board]
        if depth in layers:
            layers[depth].append(board)
        for target in adj[blank]:
            nxt = swap(board, blank, target)
            if nxt not in distance:
                distance[nxt] = depth + 1
                queue.append((nxt, target))
    if len(distance) != 181440:
        raise RuntimeError("incomplete three-by-three BFS")
    result = []
    for depth in DEPTHS3:
        rng = random.Random(f"{seed}:three:{depth}")
        for index, board in enumerate(rng.sample(layers[depth], COUNT), 1):
            result.append(instance(3, depth, index, board))
    return result, {str(depth): len(layers[depth]) for depth in DEPTHS3}


def instance(size, depth, index, board):
    return {"instance_id": f"{size}-{depth:02d}-{index:03d}", "size": size,
            "depth": depth, "board": list(board)}


def candidate(seed, depth, index, adj):
    # Per-candidate seed avoids relying on opaque random state across interruptions.
    rng = random.Random(f"{seed}:four:{depth}:{index}")
    board = tuple(range(1, 16)) + (0,)
    blank, previous = 15, -1
    for _ in range(depth):
        choices = [i for i in adj[blank] if i != previous]
        target = rng.choice(choices)
        board = swap(board, blank, target)
        previous, blank = blank, target
    return board


class Certifier:
    def __init__(self, startup_timeout, timeout):
        self.startup_timeout = startup_timeout
        self.timeout = timeout
        self.process = None
        self.log = None

    def stop(self):
        if self.process is not None:
            if self.process.poll() is None:
                self.process.kill()
            self.process.wait()
            self.process.stdout.close()
            self.process.stdin.close()
            self.process = None
        if self.log is not None:
            self.log.close()
            self.log = None

    def receive(self, timeout):
        with selectors.DefaultSelector() as selector:
            selector.register(self.process.stdout, selectors.EVENT_READ)
            if not selector.select(timeout):
                raise TimeoutError()
            line = self.process.stdout.readline()
        if not line:
            raise RuntimeError(f"certifier exited with code {self.process.poll()}")
        return line.strip()

    def start(self):
        self.log = (OUT / "certifier.log").open("a", encoding="utf-8")
        self.process = subprocess.Popen(
            ["java", "-Xmx10g", "-cp", str(CERTIFIER_CLASSES), "stud.g01.experiment.SampleCertifier"],
            cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log,
            text=True, bufsize=1)
        try:
            if self.receive(self.startup_timeout) != "READY":
                raise RuntimeError("unexpected certifier startup response")
        except BaseException:
            self.stop()
            raise

    def solve(self, board):
        if self.process is None:
            self.start()
        try:
            self.process.stdin.write(" ".join(map(str, board)) + "\n")
            self.process.stdin.flush()
            response = self.receive(self.timeout)
            if response.startswith("OK "):
                return "success", int(response[3:])
            self.stop()
            return "error", response
        except TimeoutError:
            self.stop()
            return "timeout", None
        except (BrokenPipeError, OSError, RuntimeError, ValueError) as error:
            self.stop()
            return "error", str(error)




def compiled_classes_sha256():
    root = CERTIFIER_CLASSES
    classes = sorted(root.rglob("*.class")) if root.is_dir() else []
    if not (root / "stud/g01/experiment/SampleCertifier.class").is_file():
        raise RuntimeError("certifier is not compiled in result/experiment/certifier-classes")
    digest = hashlib.sha256()
    for path in classes:
        name = path.relative_to(root).as_posix().encode("utf-8")
        data = path.read_bytes()
        digest.update(len(name).to_bytes(4, "big"))
        digest.update(name)
        digest.update(len(data).to_bytes(8, "big"))
        digest.update(data)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed", type=int, default=20261105)
    parser.add_argument("--timeout", type=float, default=120.0, help="seconds per certification")
    parser.add_argument("--startup-timeout", type=float, default=3600.0, help="seconds to initialize PDB")
    parser.add_argument("--max-candidates", type=int, default=0, help="maximum new candidates this invocation; 0 means unlimited")
    args = parser.parse_args()
    if args.timeout <= 0 or args.startup_timeout <= 0 or args.max_candidates < 0:
        parser.error("timeouts must be positive and max-candidates nonnegative")
    config = {"version": VERSION, "seed": args.seed, "timeout_seconds": args.timeout,
              "startup_timeout_seconds": args.startup_timeout, "solver": "NPuzzleIdaStar/regular DISJOINT_PATTERN 6-6-3",
              "compiled_classes_sha256": compiled_classes_sha256()}
    OUT.mkdir(parents=True, exist_ok=True)
    journal_path = OUT / "instance_candidates.jsonl"
    records = []
    if journal_path.exists():
        # Only a torn final write can be discarded; an invalid complete line is an error.
        raw = journal_path.read_bytes()
        if raw and not raw.endswith(b"\n"):
            raw = raw[:raw.rfind(b"\n") + 1]
            with journal_path.open("wb") as handle:
                handle.write(raw)
                handle.flush()
                os.fsync(handle.fileno())
        lines = raw.decode("utf-8").splitlines()
        if not lines or json.loads(lines[0]) != {"config": config}:
            raise RuntimeError("journal configuration differs; use original settings or a new output directory")
        records = [json.loads(line) for line in lines[1:]]
    else:
        with journal_path.open("x", encoding="utf-8") as handle:
            handle.write(encode({"config": config}) + "\n")
            handle.flush()
            os.fsync(handle.fileno())

    samples, layer_sizes = three_samples(args.seed)
    accepted = {depth: [] for depth in DEPTHS4}
    counts = {depth: {"candidates": 0, "duplicates": 0, "certification_failures": 0,
                      "timeouts": 0, "errors": 0, "depth_mismatches": 0} for depth in DEPTHS4}
    seen = set()
    for record in records:
        depth = record["target_depth"]
        stat = counts[depth]
        if record["candidate_index"] != stat["candidates"]:
            raise RuntimeError("journal candidate index is inconsistent")
        stat["candidates"] += 1
        board = tuple(record["board"])
        status = record["status"]
        if status == "duplicate":
            stat["duplicates"] += 1
        else:
            if board in seen:
                raise RuntimeError("journal contains unmarked duplicate")
            seen.add(board)
            if status == "accepted":
                if record["certified_depth"] != depth or len(accepted[depth]) >= COUNT:
                    raise RuntimeError("journal contains invalid accepted instance")
                accepted[depth].append(board)
            elif status == "mismatch":
                stat["depth_mismatches"] += 1
            elif status in ("timeout", "error"):
                stat["certification_failures"] += 1
                stat["timeouts" if status == "timeout" else "errors"] += 1
            else:
                raise RuntimeError("unknown journal status")

    def publish():
        all_samples = list(samples)
        for depth in DEPTHS4:
            all_samples.extend(instance(4, depth, i, board)
                               for i, board in enumerate(accepted[depth], 1))
        data = "".join(encode(item) + "\n" for item in all_samples)
        atomic_write(OUT / "instances.jsonl", data)
        metadata = {"version": VERSION, "seed": args.seed, "complete": len(all_samples) == 720,
                    "instance_count": len(all_samples), "instances_sha256": hashlib.sha256(data.encode()).hexdigest(),
                    "goal": "row-major 1..n^2-1 followed by 0",
                    "three_by_three": {"method": "complete BFS from goal; uniform random sample without replacement per layer",
                                       "reachable_states": 181440, "layer_sizes": layer_sizes},
                    "four_by_four": {"method": "independent seeded walks of target length; immediate reversals excluded; retain only exact depth matches",
                                     "distribution": "not uniform within depth layers",
                                     "solver": config["solver"], "timeout_seconds": args.timeout,
                                     "compiled_classes_sha256": config["compiled_classes_sha256"],
                                     "startup_timeout_seconds": args.startup_timeout,
                                     "jvm_options": ["-Xmx10g"],
                                     "layers": {str(d): dict(counts[d], accepted=len(accepted[d])) for d in DEPTHS4}},
                    "candidate_journal": journal_path.name}
        atomic_write(OUT / "instance_metadata.json", json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")

    publish()
    adj = neighbors(4)
    certifier = Certifier(args.startup_timeout, args.timeout)
    attempted = 0
    try:
        with journal_path.open("a", encoding="utf-8") as journal:
            for depth in DEPTHS4:
                while len(accepted[depth]) < COUNT:
                    if args.max_candidates and attempted >= args.max_candidates:
                        print("Candidate limit reached; rerun with the same settings to resume.", file=sys.stderr)
                        return 2
                    index = counts[depth]["candidates"]
                    board = candidate(args.seed, depth, index, adj)
                    record = {"target_depth": depth, "candidate_index": index, "board": list(board)}
                    if board in seen:
                        record["status"] = "duplicate"
                    else:
                        status, result = certifier.solve(board)
                        if status == "success":
                            record["certified_depth"] = result
                            record["status"] = "accepted" if result == depth else "mismatch"
                        else:
                            record["status"] = status
                            record["detail"] = result
                    journal.write(encode(record) + "\n")
                    journal.flush()
                    os.fsync(journal.fileno())
                    counts[depth]["candidates"] += 1
                    attempted += 1
                    status = record["status"]
                    if status == "duplicate":
                        counts[depth]["duplicates"] += 1
                    else:
                        seen.add(board)
                        if status == "accepted":
                            accepted[depth].append(board)
                        elif status == "mismatch":
                            counts[depth]["depth_mismatches"] += 1
                        else:
                            counts[depth]["certification_failures"] += 1
                            counts[depth]["timeouts" if status == "timeout" else "errors"] += 1
                    publish()
                    if status == "accepted":
                        print(f"depth {depth}: {len(accepted[depth])}/{COUNT} (candidates {counts[depth]['candidates']})", flush=True)
    finally:
        certifier.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
