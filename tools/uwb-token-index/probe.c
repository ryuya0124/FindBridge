#include <dlfcn.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

// macOS arm64 research only. Fixed integers are the only inputs: no daemon,
// account, Keychain, BLE or UWB APIs are called. This private ABI may change.
// Swift getter ABI: metadata and witness table, plus self in swift_context.
typedef struct {
    uint64_t first;
    uint64_t second;
} DataWords;
typedef DataWords
    __attribute__((swiftcall)) (*DataGetter)(const void *, const void *,
                                             const void *__attribute__((swift_context)));
typedef const void *(*WitnessGetter)(const void *, const void *, const void *);
extern void referenceUInt64DataWords(uint64_t value, uint64_t *output);

int main(void) {
#if !defined(__APPLE__) || !defined(__aarch64__)
    fputs("This probe requires macOS arm64\n", stderr);
    return 2;
#endif
    void *core = dlopen("/usr/lib/swift/libswiftCore.dylib", RTLD_NOW | RTLD_LOCAL);
    void *base =
        dlopen("/System/Library/PrivateFrameworks/FindMyBase.framework/Versions/A/FindMyBase",
               RTLD_NOW | RTLD_LOCAL);
    if (!core || !base) {
        fputs("Required Apple library unavailable\n", stderr);
        return 2;
    }
    const void *metadata = dlsym(core, "$ss6UInt64VN");
    const void *descriptor = dlsym(core, "$ss6UInt64Vs17FixedWidthIntegersMc");
    WitnessGetter witnessFor = (WitnessGetter)dlsym(core, "swift_getWitnessTable");
    DataGetter getter =
        (DataGetter)dlsym(base, "$ss17FixedWidthIntegerP10FindMyBaseE4data10Foundation4DataVvg");
    if (!metadata || !descriptor || !witnessFor || !getter) {
        fputs("Expected pure metadata/getter symbols unavailable\n", stderr);
        return 2;
    }
    const void *witness = witnessFor(descriptor, metadata, NULL);
    if (!witness)
        return 2;
    const uint64_t values[] = {0, 1, UINT64_C(0x0102030405060708), UINT64_MAX};
    for (size_t i = 0; i < sizeof(values) / sizeof(values[0]); ++i) {
        uint64_t expected[2];
        referenceUInt64DataWords(values[i], expected);
        DataWords actual = getter(metadata, witness, &values[i]);
        if (actual.first != expected[0] || actual.second != expected[1]) {
            fputs("Pure integer-data representation mismatch\n", stderr);
            return 1;
        }
    }
    puts("FindMyBase UInt64.data matches native eight-byte copies: 4 fixtures");
    puts("Index 0x0102030405060708 info bytes: 0807060504030201");
    return 0;
}
