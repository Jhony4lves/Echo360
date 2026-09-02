#include <assert.h>
#include <stdio.h>
#include <string.h>

#include "../openxechain/echo_path.h"

static void expect_route(const char *canonical, const char *expected, int require_write) {
    char output[256];
    size_t written = echo_path_to_kernel(
        canonical,
        strlen(canonical),
        output,
        sizeof(output),
        require_write
    );
    assert(written == strlen(expected));
    assert(strcmp(output, expected) == 0);
}

static void expect_rejected(const char *canonical, int require_write) {
    char output[256];
    assert(echo_path_to_kernel(
        canonical,
        strlen(canonical),
        output,
        sizeof(output),
        require_write
    ) == 0U);
}

int main(void) {
    echo_path_info info;

    expect_route("/Hdd1", "\\Device\\Harddisk0\\Partition1", 0);
    expect_route("/Hdd1/Games/Dark Souls II", "\\Device\\Harddisk0\\Partition1\\Games\\Dark Souls II", 1);
    expect_route("/Usb0/Content", "\\Device\\Mass0\\Content", 1);
    expect_route("/Flash", "\\SystemRoot", 0);

    info = echo_classify_canonical_path("/Flash", strlen("/Flash"));
    assert(info.device == ECHO_DEVICE_FLASH);
    assert(info.writable == 0);
    expect_rejected("/Flash", 1);
    expect_rejected("/Flash/launch.xex", 1);

    expect_rejected("/Hdd1/../Flash", 0);
    expect_rejected("/Hdd1/./Games", 0);
    expect_rejected("/Hdd1//Games", 0);
    expect_rejected("/Hdd1/Games/", 0);
    expect_rejected("/Hdd1:C/Games", 0);
    expect_rejected("/Hdd1\\Games", 0);
    expect_rejected("Hdd1/Games", 0);
    expect_rejected("/Unknown/Games", 0);

    puts("EchoCore path tests: OK");
    return 0;
}
