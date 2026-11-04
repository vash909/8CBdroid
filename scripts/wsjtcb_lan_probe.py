#!/usr/bin/env python3
"""Send simulated desktop heartbeat/status packets to the phone; never control a radio."""
import argparse
import socket
import struct
import time

MAGIC = 0xADBCCBDA


def utf8(value):
    data = value.encode("utf-8")
    return struct.pack(">I", len(data)) + data


def header(schema, kind, instance):
    return struct.pack(">III", MAGIC, schema, kind) + utf8(instance)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True, help="Phone Wi-Fi IPv4 address")
    parser.add_argument("--port", type=int, default=2237)
    parser.add_argument("--count", type=int, default=3)
    parser.add_argument("--interval", type=float, default=5)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or args.count < 1 or args.interval <= 0:
        parser.error("Use a valid port and positive count/interval")
    socket.inet_pton(socket.AF_INET, args.host)
    destination = (args.host, args.port)
    instance = "8CBdroid-LAN-probe"
    schema = 2
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as desktop:
        desktop.bind(("0.0.0.0", 0))
        desktop.settimeout(3)
        print(f"Simulated desktop source port: {desktop.getsockname()[1]}", flush=True)
        try:
            for _ in range(args.count):
                heartbeat = header(schema, 0, instance) + struct.pack(">I", 3) + utf8("probe") + utf8("1")
                desktop.sendto(heartbeat, destination)
                reply, source = desktop.recvfrom(65507)
                if source != destination or len(reply) < 16:
                    raise ValueError("Unexpected heartbeat endpoint or truncated reply")
                magic, schema, kind, length = struct.unpack(">IIII", reply[:16])
                if magic != MAGIC or schema not in (2, 3) or kind != 0 or reply[16:16 + length].decode() != instance:
                    raise ValueError("Unexpected heartbeat header")
                status = header(schema, 1, instance) + struct.pack(">Q", 27_265_000)
                status += utf8("FT8") + utf8("26AT101") + utf8("-12") + utf8("FT8")
                status += bytes((1, 0, 1))
                desktop.sendto(status, destination)
                print(f"Heartbeat reply from {source[0]}:{source[1]}, schema {schema}; sent simulated FT8 status", flush=True)
                time.sleep(args.interval)
        except KeyboardInterrupt:
            print("Stopped by user")
        finally:
            desktop.sendto(header(schema, 6, instance), destination)
            print("Sent desktop Close; the phone should return to waiting for a desktop", flush=True)


if __name__ == "__main__":
    main()
