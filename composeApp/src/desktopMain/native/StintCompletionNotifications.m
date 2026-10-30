#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>
#import <UserNotifications/UserNotifications.h>
#import <jni.h>

static NSString *const StintIdentifierPrefix = @"stint.timer.";

@interface StintNotificationDelegate : NSObject <UNUserNotificationCenterDelegate>
@end

@implementation StintNotificationDelegate
- (void)userNotificationCenter:(UNUserNotificationCenter *)center
       willPresentNotification:(UNNotification *)notification
         withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completionHandler {
    completionHandler(UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionList | UNNotificationPresentationOptionSound);
}
@end

static StintNotificationDelegate *StintDelegate;

static id StintWaitWithTimeout(void (^operation)(void (^)(id)), NSTimeInterval seconds) {
    dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);
    __block id result = nil;
    operation(^(id value) {
        result = value;
        dispatch_semaphore_signal(semaphore);
    });
    dispatch_time_t timeout = dispatch_time(
        DISPATCH_TIME_NOW,
        (int64_t)(seconds * NSEC_PER_SEC)
    );
    return dispatch_semaphore_wait(semaphore, timeout) == 0 ? result : nil;
}

static id StintWait(void (^operation)(void (^)(id))) {
    return StintWaitWithTimeout(operation, 3);
}

static UNNotificationSettings *StintSettings(UNUserNotificationCenter *center) {
    return StintWait(^(void (^done)(id)) {
        [center getNotificationSettingsWithCompletionHandler:done];
    });
}

static NSArray<UNNotificationRequest *> *StintPending(UNUserNotificationCenter *center) {
    return StintWait(^(void (^done)(id)) {
        [center getPendingNotificationRequestsWithCompletionHandler:done];
    });
}

static NSSet<NSString *> *StintDelivered(UNUserNotificationCenter *center) {
    NSArray<UNNotification *> *notifications = StintWait(^(void (^done)(id)) {
        [center getDeliveredNotificationsWithCompletionHandler:done];
    });
    if (notifications == nil) return nil;
    NSMutableSet<NSString *> *identifiers = [NSMutableSet set];
    for (UNNotification *notification in notifications) {
        [identifiers addObject:notification.request.identifier];
    }
    return identifiers;
}

static NSString *StintAuthorizationName(UNAuthorizationStatus status) {
    switch (status) {
        case UNAuthorizationStatusNotDetermined: return @"notDetermined";
        case UNAuthorizationStatusDenied: return @"denied";
        case UNAuthorizationStatusAuthorized: return @"authorized";
        case UNAuthorizationStatusProvisional: return @"provisional";
        default: return @"unknown";
    }
}

static BOOL StintValidNotice(NSDictionary *notice) {
    return [notice isKindOfClass:NSDictionary.class] &&
        [notice[@"identifier"] isKindOfClass:NSString.class] &&
        [notice[@"identifier"] hasPrefix:StintIdentifierPrefix] &&
        [notice[@"title"] isKindOfClass:NSString.class] &&
        [notice[@"body"] isKindOfClass:NSString.class];
}

static NSString *StintAddNotice(
    UNUserNotificationCenter *center,
    NSDictionary *notice,
    BOOL immediate
) {
    UNMutableNotificationContent *content = [[UNMutableNotificationContent alloc] init];
    content.title = notice[@"title"];
    content.body = notice[@"body"];
    content.sound = UNNotificationSound.defaultSound;
    content.categoryIdentifier = @"STINT_TIMER_COMPLETION";
    content.threadIdentifier = @"stint.timer-completion";
    UNNotificationTrigger *trigger = nil;
    if (!immediate) {
        NSTimeInterval delay = MAX([notice[@"delaySeconds"] doubleValue], 0.1);
        trigger = [UNTimeIntervalNotificationTrigger triggerWithTimeInterval:delay repeats:NO];
    }
    UNNotificationRequest *request = [UNNotificationRequest
        requestWithIdentifier:notice[@"identifier"] content:content trigger:trigger];
    id result = StintWait(^(void (^done)(id)) {
        [center addNotificationRequest:request withCompletionHandler:^(NSError *value) {
            done(value ?: NSNull.null);
        }];
    });
    if (result == nil) return @"notification-timeout";
    return [result isKindOfClass:NSError.class] ? [result localizedDescription] : nil;
}

static NSDictionary *StintHandleCommand(NSDictionary *command) {
    UNUserNotificationCenter *center = UNUserNotificationCenter.currentNotificationCenter;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        StintDelegate = [[StintNotificationDelegate alloc] init];
        dispatch_sync(dispatch_get_main_queue(), ^{
            center.delegate = StintDelegate;
        });
    });

    NSString *name = command[@"command"];
    if ([name isEqualToString:@"authorize"]) {
        UNNotificationSettings *settings = StintSettings(center);
        if (settings.authorizationStatus == UNAuthorizationStatusNotDetermined) {
            StintWaitWithTimeout(^(void (^done)(id)) {
                [center requestAuthorizationWithOptions:
                    (UNAuthorizationOptionAlert | UNAuthorizationOptionSound)
                    completionHandler:^(BOOL granted, NSError *error) {
                        done(error ?: NSNull.null);
                    }];
            }, 60);
            settings = StintSettings(center);
        }
        return @{
            @"ok": @YES,
            @"authorization": StintAuthorizationName(settings.authorizationStatus),
        };
    }
    if ([name isEqualToString:@"status"]) {
        NSArray<UNNotificationRequest *> *requests = StintPending(center);
        NSSet<NSString *> *deliveredIdentifiers = StintDelivered(center);
        if (requests == nil || deliveredIdentifiers == nil) {
            return @{@"ok": @NO, @"error": @"notification-timeout"};
        }
        NSMutableArray<NSString *> *pending = [NSMutableArray array];
        for (UNNotificationRequest *request in requests) {
            if ([request.identifier hasPrefix:StintIdentifierPrefix]) {
                [pending addObject:request.identifier];
            }
        }
        NSMutableArray<NSString *> *delivered = [NSMutableArray array];
        for (NSString *identifier in deliveredIdentifiers) {
            if ([identifier hasPrefix:StintIdentifierPrefix] ||
                [identifier hasPrefix:@"stint.test."]) {
                [delivered addObject:identifier];
            }
        }
        [pending sortUsingSelector:@selector(compare:)];
        [delivered sortUsingSelector:@selector(compare:)];
        UNNotificationSettings *settings = StintSettings(center);
        return @{
            @"ok": @YES,
            @"authorization": StintAuthorizationName(settings.authorizationStatus),
            @"pendingIdentifiers": pending,
            @"deliveredIdentifiers": delivered,
        };
    }
    if ([name isEqualToString:@"test"]) {
        UNNotificationSettings *settings = StintSettings(center);
        BOOL allowed = settings.authorizationStatus == UNAuthorizationStatusAuthorized ||
            settings.authorizationStatus == UNAuthorizationStatusProvisional;
        if (!allowed) {
            return @{
                @"ok": @NO,
                @"authorization": StintAuthorizationName(settings.authorizationStatus),
                @"error": @"not-authorized",
            };
        }
        NSString *identifier = [@"stint.test." stringByAppendingString:NSUUID.UUID.UUIDString];
        NSDictionary *notice = @{
            @"identifier": identifier,
            @"title": @"알림 테스트",
            @"body": @"집중과 휴식이 끝나면 이렇게 알려드려요.",
        };
        NSString *error = StintAddNotice(center, notice, YES);
        return @{
            @"ok": (error == nil ? @YES : @NO),
            @"authorization": StintAuthorizationName(settings.authorizationStatus),
            @"identifier": identifier,
            @"error": error ?: @"",
        };
    }
    if (![name isEqualToString:@"sync"]) {
        return @{@"ok": @NO, @"error": @"unknown-command"};
    }

    NSDictionary *scheduled = StintValidNotice(command[@"scheduled"])
        ? command[@"scheduled"] : nil;
    NSDictionary *immediate = StintValidNotice(command[@"immediate"])
        ? command[@"immediate"] : nil;
    NSArray<UNNotificationRequest *> *requests = StintPending(center);
    if (requests == nil) {
        return @{@"ok": @NO, @"error": @"notification-timeout"};
    }
    NSMutableArray<NSString *> *pendingIdentifiers = [NSMutableArray array];
    for (UNNotificationRequest *request in requests) {
        if ([request.identifier hasPrefix:StintIdentifierPrefix] &&
            ![request.identifier isEqualToString:scheduled[@"identifier"]]) {
            [pendingIdentifiers addObject:request.identifier];
        }
    }
    if (pendingIdentifiers.count > 0) {
        [center removePendingNotificationRequestsWithIdentifiers:pendingIdentifiers];
    }
    if (scheduled != nil) {
        [center removePendingNotificationRequestsWithIdentifiers:@[scheduled[@"identifier"]]];
    }

    UNNotificationSettings *currentSettings = StintSettings(center);
    BOOL notificationsAllowed =
        currentSettings.authorizationStatus == UNAuthorizationStatusAuthorized ||
        currentSettings.authorizationStatus == UNAuthorizationStatusProvisional;
    if ((scheduled != nil || immediate != nil) && !notificationsAllowed) {
        return @{
            @"ok": @NO,
            @"authorization": StintAuthorizationName(currentSettings.authorizationStatus),
            @"error": @"not-authorized",
        };
    }

    NSString *error = nil;
    if (scheduled != nil) {
        NSString *identifier = scheduled[@"identifier"];
        NSSet<NSString *> *delivered = StintDelivered(center);
        if (delivered == nil) return @{@"ok": @NO, @"error": @"notification-timeout"};
        if (![delivered containsObject:identifier]) {
            error = StintAddNotice(center, scheduled, NO);
        }
    }
    if (immediate != nil && error == nil) {
        NSString *identifier = immediate[@"identifier"];
        [center removePendingNotificationRequestsWithIdentifiers:@[identifier]];
        NSSet<NSString *> *delivered = StintDelivered(center);
        if (delivered == nil) return @{@"ok": @NO, @"error": @"notification-timeout"};
        if (![delivered containsObject:identifier]) {
            error = StintAddNotice(center, immediate, YES);
        }
    }
    UNNotificationSettings *settings = StintSettings(center);
    return @{
        @"ok": (error == nil ? @YES : @NO),
        @"authorization": StintAuthorizationName(settings.authorizationStatus),
        @"error": error ?: @"",
    };
}

JNIEXPORT jstring JNICALL
Java_dev_stint_app_NativeDesktopNotificationBridge_nativeCommand(
    JNIEnv *env,
    jobject self,
    jstring json
) {
    if (json == NULL) return NULL;
    jsize length = (*env)->GetStringLength(env, json);
    const jchar *characters = (*env)->GetStringChars(env, json, NULL);
    NSString *input = [[NSString alloc]
        initWithCharacters:(const unichar *)characters length:(NSUInteger)length];
    (*env)->ReleaseStringChars(env, json, characters);
    NSData *data = [input dataUsingEncoding:NSUTF8StringEncoding];
    NSError *parseError = nil;
    NSDictionary *command = [NSJSONSerialization JSONObjectWithData:data options:0 error:&parseError];
    NSDictionary *response;
    if (![NSBundle.mainBundle.bundleIdentifier isEqualToString:@"dev.stint.app"]) {
        response = @{@"ok": @NO, @"error": @"unavailable-bundle"};
    } else {
        response = parseError == nil && [command isKindOfClass:NSDictionary.class]
            ? StintHandleCommand(command)
            : @{@"ok": @NO, @"error": @"invalid-command"};
    }
    NSData *responseData = [NSJSONSerialization dataWithJSONObject:response options:0 error:nil];
    NSString *responseString = [[NSString alloc] initWithData:responseData encoding:NSUTF8StringEncoding];
    NSUInteger responseLength = responseString.length;
    unichar *responseCharacters = calloc(responseLength, sizeof(unichar));
    [responseString getCharacters:responseCharacters range:NSMakeRange(0, responseLength)];
    jstring result = (*env)->NewString(
        env,
        (const jchar *)responseCharacters,
        (jsize)responseLength
    );
    free(responseCharacters);
    return result;
}
