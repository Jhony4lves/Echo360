#!/usr/bin/env python3
import socket
import struct
import sys
import time

MAGIC = 0x4543484F
VERSION = 1
PING = 0x01
PONG = 0x02
HEADER = struct.Struct(">IBBHII")


def recv_exact(sock: socket.socket, length: int) -> bytes:
    chunks = []
    remaining = length
    while remaining:
        chunk = sock.recv(remaining)
        if not chunk:
            raise RuntimeError("connection closed before frame completed")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def main() -> int:
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 36000
    request_id = 1
    nonce = time.time_ns() & 0xFFFFFFFFFFFFFFFF
    payload = struct.pack(">Q", nonce)
    frame = HEADER.pack(MAGIC, VERSION, PING, 0, len(payload), request_id) + payload

    started = time.perf_counter()
    with socket.create_connection((host, port), timeout=2.5) as sock:
        sock.settimeout(2.5)
        sock.sendall(frame)
        raw_header = recv_exact(sock, HEADER.size)
        magic, version, frame_type, flags, payload_length, response_id = HEADER.unpack(raw_header)
        response_payload = recv_exact(sock, payload_length)
    latency_ms = (time.perf_counter() - started) * 1000

    if magic != MAGIC or version != VERSION or frame_type != PONG:
        raise RuntimeError("invalid EchoLink PONG header")
    if flags != 0 or response_id != request_id or response_payload != payload:
        raise RuntimeError("EchoLink PONG did not echo request identity")

    print(f"PONG v{version} {latency_ms:.2f} ms")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
