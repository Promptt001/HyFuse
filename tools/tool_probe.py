#!/usr/bin/env python3
"""Generic WebSocket probe for the MC Agent Fabric bridge.

Calls ANY tool with arbitrary JSON args. Dependency-free.

Usage:
  python3 tool_probe.py --host <client IP> --token <token> <tool> [JSON_ARGS]
  python3 tool_probe.py get-vitals
  python3 tool_probe.py get-block-info '{"x":729001,"y":70,"z":-1284110}'
  python3 tool_probe.py find-blocks '{"blockType":"stone","maxDistance":8,"count":3}'

If JSON_ARGS is omitted, "{}" is used. Results printed as pretty JSON.
"""
import argparse, base64, hashlib, json, os, socket, struct, sys


def recv_exact(sock, count):
    data = b""
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise RuntimeError("connection closed")
        data += chunk
    return data


def send_frame(sock, payload: bytes, opcode: int = 0x1):
    mask = os.urandom(4)
    length = len(payload)
    if length < 126:
        prefix = bytes([0x80 | opcode, 0x80 | length])
    elif length < 65536:
        prefix = bytes([0x80 | opcode, 0x80 | 126]) + struct.pack("!H", length)
    else:
        prefix = bytes([0x80 | opcode, 0x80 | 127]) + struct.pack("!Q", length)
    masked = bytes(byte ^ mask[i % 4] for i, byte in enumerate(payload))
    sock.sendall(prefix + mask + masked)


def recv_frame(sock):
    first, second = recv_exact(sock, 2)
    opcode = first & 0x0F
    length = second & 0x7F
    if length == 126:
        length = struct.unpack("!H", recv_exact(sock, 2))[0]
    elif length == 127:
        length = struct.unpack("!Q", recv_exact(sock, 8))[0]
    masked = second & 0x80
    if masked:
        mask = recv_exact(sock, 4)
    data = recv_exact(sock, length)
    if masked:
        data = bytes(byte ^ mask[i % 4] for i, byte in enumerate(data))
    return opcode, data


def call_tool(host, port, token, tool, args, timeout=15):
    key = base64.b64encode(os.urandom(16)).decode()
    with socket.create_connection((host, port), timeout=timeout) as sock:
        request = (
            f"GET / HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\n"
            f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n"
            f"Sec-WebSocket-Version: 13\r\n\r\n"
        )
        sock.sendall(request.encode())
        headers = b""
        while b"\r\n\r\n" not in headers:
            headers += sock.recv(4096)
        expected = base64.b64encode(
            hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()
        )
        status_line = headers.split(b"\r\n", 1)[0]
        if not status_line.startswith(b"HTTP/1.1 101 ") or expected.lower() not in headers.lower():
            raise RuntimeError(f"handshake failed: {headers!r}")
        payload = {"id": "probe-1", "tool": tool, "args": args}
        if token:
            payload["token"] = token
        send_frame(sock, json.dumps(payload, separators=(",", ":")).encode())
        opcode, data = recv_frame(sock)
        return json.loads(data.decode())


def main():
    parser = argparse.ArgumentParser(description="Generic Fabric bridge tool probe")
    parser.add_argument("tool", help="Tool name, e.g. get-vitals")
    parser.add_argument("args", nargs="?", default="{}", help="JSON args object, default {}")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25580)
    parser.add_argument("--token", default=os.getenv("MCAGENT_TOKEN", ""))
    parser.add_argument("--timeout", type=int, default=15)
    a = parser.parse_args()
    try:
        args_obj = json.loads(a.args)
    except json.JSONDecodeError as e:
        print(f"ERROR: invalid JSON args: {e}", file=sys.stderr)
        sys.exit(2)
    if not isinstance(args_obj, dict):
        print("ERROR: args must be a JSON object", file=sys.stderr)
        sys.exit(2)
    try:
        result = call_tool(a.host, a.port, a.token, a.tool, args_obj, a.timeout)
    except Exception as e:
        print(f"ERROR: {type(e).__name__}: {e}", file=sys.stderr)
        sys.exit(1)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
