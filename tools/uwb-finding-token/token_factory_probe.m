#import <Foundation/Foundation.h>
#include <dlfcn.h>
#import <objc/message.h>
#import <objc/runtime.h>

// Fixed synthetic IRKs only. The inspected factory builds an in-memory OPACK
// dictionary; no NISession, daemon, account, Keychain or radio API is invoked.
static NSString *hex(NSData *data) {
    NSMutableString *text = [NSMutableString stringWithCapacity:data.length * 2];
    for (NSUInteger i = 0; i < data.length; ++i)
        [text appendFormat:@"%02x", ((const uint8_t *)data.bytes)[i]];
    return text;
}
int main(void) {
    @autoreleasepool {
        void *library = dlopen(
            "/System/Library/Frameworks/NearbyInteraction.framework/Versions/A/NearbyInteraction",
            RTLD_NOW | RTLD_LOCAL);
        Class cls = NSClassFromString(@"NIDiscoveryToken");
        SEL factory = NSSelectorFromString(@"generateFindingTokenWithIRK:");
        SEL raw = NSSelectorFromString(@"rawToken");
        SEL irk = NSSelectorFromString(@"getIRK");
        SEL identifier = NSSelectorFromString(@"getSessionIdentifier");
        SEL variant = NSSelectorFromString(@"tokenVariant");
        if (!library || !cls || ![cls respondsToSelector:factory])
            return 2;
        NSMutableArray *rows = [NSMutableArray array];
        for (int fixture = 0; fixture < 3; ++fixture) {
            uint8_t bytes[16];
            for (int i = 0; i < 16; ++i)
                bytes[i] = fixture == 0 ? i : fixture == 1 ? 0 : 255;
            NSData *input = [NSData dataWithBytes:bytes length:16];
            id token = ((id (*)(id, SEL, id))objc_msgSend)(cls, factory, input);
            if (!token || ![token respondsToSelector:raw] || ![token respondsToSelector:irk] ||
                ![token respondsToSelector:identifier] || ![token respondsToSelector:variant])
                return 2;
            NSData *stored = ((id (*)(id, SEL))objc_msgSend)(token, irk);
            NSData *session = ((id (*)(id, SEL))objc_msgSend)(token, identifier);
            NSData *encoded = ((id (*)(id, SEL))objc_msgSend)(token, raw);
            NSInteger kind = ((NSInteger (*)(id, SEL))objc_msgSend)(token, variant);
            if (![stored isKindOfClass:NSData.class] || ![session isKindOfClass:NSData.class] ||
                ![encoded isKindOfClass:NSData.class] || ![stored isEqual:input])
                return 1;
            [rows addObject:@{
                @"irk" : hex(stored),
                @"identifier" : hex(session),
                @"variant" : @(kind),
                @"raw_opack" : hex(encoded)
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
