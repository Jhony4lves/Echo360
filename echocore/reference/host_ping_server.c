#include <arpa/inet.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define ECHO_MAGIC 0x4543484fU
#define ECHO_VERSION 1U
#define ECHO_TYPE_PING 0x01U
#define ECHO_TYPE_PONG 0x02U
#define ECHO_HEADER_BYTES 16U
#define ECHO_MAX_CONTROL_PAYLOAD (1024U * 1024U)
#define ECHO_DEFAULT_PORT 36000

struct echo_header {
    uint32_t magic;
    uint8_t version;
    uint8_t type;
    uint16_t flags;
    uint32_t payload_length;
    uint32_t request_id;
};

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
    uint8_t raw[ECHO_HEADER_BYTES];
    if (read_exact(fd, raw, sizeof(raw)) != 0) return 2;

    struct echo_header header;
    uint32_t value32;
    uint16_t value16;

    memcpy(&value32, raw + 0, sizeof(value32));
    header.magic = ntohl(value32);
    header.version = raw[4];
    header.type = raw[5];
    memcpy(&value16, raw + 6, sizeof(value16));
    header.flags = ntohs(value16);
    memcpy(&value32, raw + 8, sizeof(value32));
    header.payload_length = ntohl(value32);
    memcpy(&value32, raw + 12, sizeof(value32));
    header.request_id = ntohl(value32);

    if (header.magic != ECHO_MAGIC ||
        header.version != ECHO_VERSION ||
        header.type != ECHO_TYPE_PING ||
        header.payload_length > ECHO_MAX_CONTROL_PAYLOAD) {
        return 3;
    }

    uint8_t *payload = NULL;
    if (header.payload_length > 0) {
        payload = (uint8_t *)malloc(header.payload_length);
        if (payload == NULL) return 4;
        if (read_exact(fd, payload, header.payload_length) != 0) {
            free(payload);
            return 5;
        }
    }

    memset(raw, 0, sizeof(raw));
    value32 = htonl(ECHO_MAGIC);
    memcpy(raw + 0, &value32, sizeof(value32));
    raw[4] = ECHO_VERSION;
    raw[5] = ECHO_TYPE_PONG;
    value16 = htons(header.flags);
    memcpy(raw + 6, &value16, sizeof(value16));
    value32 = htonl(header.payload_length);
    memcpy(raw + 8, &value32, sizeof(value32));
    value32 = htonl(header.request_id);
    memcpy(raw + 12, &value32, sizeof(value32));

    int result = 0;
    if (write_exact(fd, raw, sizeof(raw)) != 0 ||
        (header.payload_length > 0 && write_exact(fd, payload, header.payload_length) != 0)) {
        result = 6;
    }

    free(payload);
    return result;
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
