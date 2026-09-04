#include <arpa/inet.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "../openxechain/echo_protocol.h"

#define ECHO_DEFAULT_PORT 36000

static int read_exact(int fd, void *buffer, size_t length) {
    uint8_t *cursor = (uint8_t *)buffer;
    size_t total = 0;
    while (total < length) {
        ssize_t n = recv(fd, cursor + total, length - total, 0);
        if (n == 0) return -1;
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        total += (size_t)n;
    }
    return 0;
}

static int write_exact(int fd, const void *buffer, size_t length) {
    const uint8_t *cursor = (const uint8_t *)buffer;
    size_t total = 0;
    while (total < length) {
        ssize_t n = send(fd, cursor + total, length - total, 0);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        total += (size_t)n;
    }
    return 0;
}

static int handle_client(int fd) {
    uint8_t header[ECHO_HEADER_BYTES];
    uint8_t nonce[ECHO_PING_PAYLOAD_BYTES];

    if (read_exact(fd, header, sizeof(header)) != 0) return 2;
    if (echo_validate_ping_header(header) != 0) return 3;
    if (read_exact(fd, nonce, sizeof(nonce)) != 0) return 4;

    echo_make_pong_header(header);

    if (write_exact(fd, header, sizeof(header)) != 0 ||
        write_exact(fd, nonce, sizeof(nonce)) != 0) {
        return 5;
    }
    return 0;
}

int main(int argc, char **argv) {
    int port = ECHO_DEFAULT_PORT;
    if (argc > 1) {
        port = atoi(argv[1]);
    }
    if (port < 1 || port > 65535) {
        fprintf(stderr, "invalid port\n");
        return 64;
    }

    int server = socket(AF_INET, SOCK_STREAM, 0);
    if (server < 0) {
        perror("socket");
        return 1;
    }

    int reuse = 1;
    (void)setsockopt(server, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

    struct sockaddr_in address;
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons((uint16_t)port);

    if (bind(server, (struct sockaddr *)&address, sizeof(address)) != 0) {
        perror("bind");
        close(server);
        return 1;
    }
    if (listen(server, 1) != 0) {
        perror("listen");
        close(server);
        return 1;
    }

    printf("EchoLink reference server listening on 127.0.0.1:%d\n", port);
    fflush(stdout);

    int client = accept(server, NULL, NULL);
    if (client < 0) {
        perror("accept");
        close(server);
        return 1;
    }

    int result = handle_client(client);
    close(client);
    close(server);
    return result;
}
