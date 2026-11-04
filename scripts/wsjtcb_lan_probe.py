#!/usr/bin/env python3
"""Simulate WSJT-CB heartbeat, status, decodes and Replay responses; never control a radio."""
import argparse
from datetime import datetime, timezone
import socket
import struct
import time

MAGIC = 0xADBCCBDA


def utf8(value):
    data = value.encode("utf-8")
    return struct.pack(">I", len(data)) + data


def header(schema, kind, instance):
    return struct.pack(">III", MAGIC, schema, kind) + utf8(instance)


def status(schema, instance):
    return (header(schema, 1, instance) + struct.pack(">Q", 27_265_000)
            + utf8("FT8") + utf8("26AT101") + utf8("-12") + utf8("FT8") + bytes((1, 0, 1)))


def decode(schema, instance, sample, is_new):
    millis, snr, delta, offset, message, low, off_air = sample
    return (header(schema, 2, instance) + bytes((is_new,))
            + struct.pack(">IidI", millis, snr, delta, offset)
            + utf8("~") + utf8(message) + bytes((low, off_air)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True, help="Phone Wi-Fi IPv4 address")
    parser.add_argument("--port", type=int, default=2237)
    parser.add_argument("--count", type=int, default=6)
    parser.add_argument("--interval", type=float, default=5)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or args.count < 1 or args.interval <= 0:
        parser.error("Use a valid port and positive count/interval")
    try:
        socket.inet_pton(socket.AF_INET, args.host)
    except OSError:
        parser.error("Enter the phone's IPv4 address")
    destination = (args.host, args.port)
    instance = "8CBdroid-LAN-probe"
    schema = 2
    history = []
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as desktop:
        desktop.bind(("0.0.0.0", 0))
        print(f"Simulated desktop source port: {desktop.getsockname()[1]}", flush=True)

        def receive(timeout):
            nonlocal schema
            desktop.settimeout(timeout)
            reply, source = desktop.recvfrom(65507)
            if source != destination or len(reply) < 16:
                raise ValueError("Unexpected endpoint or truncated reply")
            magic, received_schema, kind, length = struct.unpack(">IIII", reply[:16])
            if (magic != MAGIC or received_schema not in (2, 3) or length > len(reply) - 16
                    or reply[16:16 + length].decode() != instance):
                raise ValueError("Unexpected message header")
            schema = received_schema
            if kind == 7:
                for sample in history:
                    desktop.sendto(decode(schema, instance, sample, False), destination)
                desktop.sendto(status(schema, instance), destination)
                print(f"Replayed {len(history)} decodes", flush=True)
            return kind

        try:
            for index in range(args.count):
                desktop.sendto(header(schema, 0, instance) + struct.pack(">I", 3) + utf8("probe") + utf8("2"), destination)
                deadline = time.monotonic() + 3
                while receive(max(0.001, deadline - time.monotonic())) != 0:
                    if time.monotonic() >= deadline:
                        raise TimeoutError("No heartbeat reply received")
                now = datetime.now(timezone.utc)
                millis = ((now.hour * 60 + now.minute) * 60 + now.second) * 1000 + now.microsecond // 1000
                sample = (millis, -17, 0.25, 1000 + index * 10, f"CQ 1AT{106 + index:03d} JN45",
                          index % 3 == 1, index % 3 == 2)
                history.append(sample)
                desktop.sendto(status(schema, instance), destination)
                desktop.sendto(decode(schema, instance, sample, True), destination)
                print(f"Sent live decode {sample[4]} using schema {schema}", flush=True)
                deadline = time.monotonic() + args.interval
                while time.monotonic() < deadline:
                    try:
                        receive(max(0.001, deadline - time.monotonic()))
                    except socket.timeout:
                        break
        except KeyboardInterrupt:
            print("Stopped by user")
        except (OSError, ValueError) as error:
            parser.exit(1, f"Probe failed: {error}\n")
        finally:
            try:
                desktop.sendto(header(schema, 6, instance), destination)
                print("Sent desktop Close; the phone should return to waiting for a desktop", flush=True)
            except OSError:
                pass


if __name__ == "__main__":
    main()
