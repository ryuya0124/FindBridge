#import <Foundation/Foundation.h>
#include <dlfcn.h>
#import <objc/message.h>

// Fixed synthetic manufacturer data only. CBDevice's in-memory parser does not
// create a Bluetooth manager, scan, advertise, connect, or invoke a daemon.
static NSData *bytes(NSString *text) {
    NSMutableData *data = [NSMutableData data];
    for (NSUInteger index = 0; index < text.length; index += 2) {
        unsigned value = 0;
        [[NSScanner scannerWithString:[text substringWithRange:NSMakeRange(index, 2)]]
            scanHexInt:&value];
        uint8_t byte = (uint8_t)value;
        [data appendBytes:&byte length:1];
    }
    return data;
}

static id hex(id value) {
    if (![value isKindOfClass:NSData.class]) return NSNull.null;
    NSData *data = value;
    NSMutableString *text = [NSMutableString stringWithCapacity:data.length * 2];
    for (NSUInteger index = 0; index < data.length; ++index) {
        [text appendFormat:@"%02x", ((const uint8_t *)data.bytes)[index]];
    }
    return text;
}

static id get(id object, NSString *selector) {
    SEL method = NSSelectorFromString(selector);
    if (![object respondsToSelector:method]) return nil;
    return ((id (*)(id, SEL))objc_msgSend)(object, method);
}

int main(void) {
    @autoreleasepool {
        void *library = dlopen(
            "/System/Library/Frameworks/CoreBluetooth.framework/Versions/A/CoreBluetooth",
            RTLD_NOW | RTLD_LOCAL);
        Class cls = NSClassFromString(@"CBDevice");
        SEL parse = NSSelectorFromString(@"_parseAppleManufacturerPtr:end:");
        if (!library || !cls || ![cls instancesRespondToSelector:parse]) return 2;
        NSArray *fixtures = @[
            @"13094906aba90117025a00",
            @"130bc906aba90117025a00017f",
            @"13068106aba9017f",
            @"130c4a06aba9aabbcc0117025a00",
            @"1a060101eabec27f",
            @"ff01aa13094906aba90117025a001a060101eabec27f",
            @"1c094906aba90117025a00",
            @"13e94906aba90117025a00",
        ];
        NSMutableArray *rows = [NSMutableArray array];
        for (NSString *fixture in fixtures) {
            id device = [[cls alloc] init];
            NSData *input = bytes(fixture);
            const uint8_t *start = input.bytes;
            ((void (*)(id, SEL, const uint8_t *, const uint8_t *))objc_msgSend)(
                device, parse, start, start + input.length);
            NSMutableArray *identifiers = [NSMutableArray array];
            for (id identifier in get(device, @"spatialInteractionIdentifiers")) {
                [identifiers addObject:hex(identifier)];
            }
            [rows addObject:@{
                @"manufacturer_data" : fixture,
                @"spatial_identifiers" : identifiers,
                @"uwb_config" : hex(get(device, @"spatialInteractionUWBConfigData")),
                @"presence" : hex(get(device, @"spatialInteractionPresenceConfigData")),
                @"no_wake_auth_tag" : hex(get(device, @"nearbyActionNoWakeAuthTagData")),
                @"no_wake_config" : hex(get(device, @"nearbyActionNoWakeConfigData")),
            }];
        }
        NSData *output = [NSJSONSerialization dataWithJSONObject:rows
                                                         options:NSJSONWritingPrettyPrinted
                                                           error:nil];
        fwrite(output.bytes, 1, output.length, stdout);
        puts("");
    }
    return 0;
}
