#include <assert.h>
#include <stdint.h>
#include <string.h>

#include "echo_path_policy.h"
#include "echo_readonly_contract.h"

int main(void) {
    uint8_t info[ECHO_CORE_INFO_BYTES];
    uint8_t stat[ECHO_FILE_STAT_BYTES];
    char native[ECHO_NATIVE_PATH_MAX];
    size_t native_len = 0U;
    const char good1[] = "Hdd1:/Content/0000000000000000/465307E4/00007000/default.xex";
    const char good2[] = "hDd1:\\Content\\0000000000000000\\465307E4\\00000002\\file";
    const char bad_parent[] = "Hdd1:/Content/../flash.xex";
    const char bad_root[] = "Usb0:/Content/file";
    const char bad_double_separator[] = "Hdd1://Content/file";
    const char bad_ftp_alias[] = "fHdd:/Content/file";
    const char bad_colon[] = "Hdd1:/Content/bad:name";

    echo_ro_make_core_info(
        info,
        0x00010000U,
        0x20449700U,
        0x465307E4U,
        ECHO_CAP_PING | ECHO_CAP_CORE_INFO | (UINT64_C(1) << 63),
        ECHO_CORE_STATUS_NETWORK_LINK_ACTIVE
    );

    assert(echo_ro_read_be16(info + 0U) == ECHO_RO_CONTRACT_VERSION);
    assert(echo_ro_read_be16(info + 2U) == 0U);
    assert(echo_ro_read_be32(info + 4U) == 0x00010000U);
    assert(echo_ro_read_be32(info + 8U) == 0x20449700U);
    assert(echo_ro_read_be32(info + 12U) == 0x465307E4U);
    assert(echo_ro_read_be64(info + 16U) == (ECHO_CAP_PING | ECHO_CAP_CORE_INFO));
    assert(echo_ro_read_be32(info + 24U) == ECHO_CORE_STATUS_NETWORK_LINK_ACTIVE);
    assert(echo_ro_read_be32(info + 28U) == 0U);

    echo_ro_make_file_stat(
        stat,
        ECHO_STATUS_OK,
        ECHO_OBJECT_FILE,
        UINT64_C(0x123456789)
    );
    assert(stat[0] == ECHO_STATUS_OK);
    assert(stat[1] == ECHO_OBJECT_FILE);
    assert(echo_ro_read_be16(stat + 2U) == 0U);
    assert(echo_ro_read_be32(stat + 4U) == 0U);
    assert(echo_ro_read_be64(stat + 8U) == UINT64_C(0x123456789));

    assert(echo_path_is_safe_readonly(good1, strlen(good1)) == 1);
    assert(echo_path_is_safe_readonly(good2, strlen(good2)) == 1);
    assert(echo_path_is_safe_readonly(bad_parent, strlen(bad_parent)) == 0);
    assert(echo_path_is_safe_readonly(bad_root, strlen(bad_root)) == 0);
    assert(echo_path_is_safe_readonly(bad_double_separator, strlen(bad_double_separator)) == 0);
    assert(echo_path_is_safe_readonly(bad_ftp_alias, strlen(bad_ftp_alias)) == 0);
    assert(echo_path_is_safe_readonly(bad_colon, strlen(bad_colon)) == 0);

    assert(echo_path_to_native_hdd1(
        good1,
        strlen(good1),
        native,
        sizeof(native),
        &native_len
    ) == 0);
    assert(strcmp(
        native,
        "\\Device\\Harddisk0\\Partition1\\Content\\0000000000000000\\465307E4\\00007000\\default.xex"
    ) == 0);
    assert(native_len == strlen(native));
    assert(echo_path_to_native_hdd1(
        bad_parent,
        strlen(bad_parent),
        native,
        sizeof(native),
        NULL
    ) == -1);
    assert(echo_path_to_native_hdd1(
        good1,
        strlen(good1),
        native,
        8U,
        NULL
    ) == -2);

    assert(echo_ro_validate_path_payload(
        (const uint8_t *)good1,
        (uint32_t)strlen(good1)
    ) == 0);

    {
        const uint8_t with_nul[] = {'H','d','d','1',':','/','x',0,'y'};
        assert(echo_ro_validate_path_payload(with_nul, sizeof(with_nul)) == -2);
    }

    assert(echo_ro_validate_path_payload(NULL, 1U) == -1);
    assert(echo_ro_validate_path_payload((const uint8_t *)good1, 0U) == -1);
    assert(echo_ro_validate_path_payload(
        (const uint8_t *)good1,
        ECHO_MAX_PATH_BYTES + 1U
    ) == -1);

    return 0;
}
