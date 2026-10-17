#!/usr/local/bin/python3
"""Fixed docker-exec entry point. The socket directory is accessible only to root."""

import socket
import sys

if __name__ == "__main__":
    data = sys.stdin.buffer.read(36 * 1024 * 1024 + 1)
    if len(data) > 36 * 1024 * 1024:
        raise SystemExit(2)
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
        # Java bounds this process by the original task deadline. The root supervisor
        # independently enforces the guest deadline and idle/collection lifetimes.
        connection.connect("/work/control/runner.sock")
        connection.sendall(data)
        connection.shutdown(socket.SHUT_WR)
        while chunk := connection.recv(65536):
            sys.stdout.buffer.write(chunk)
