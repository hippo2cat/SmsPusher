#import <Foundation/Foundation.h>
#import <AppKit/AppKit.h>
#import <UserNotifications/UserNotifications.h>
#import <dispatch/dispatch.h>
#include <stdbool.h>
#include <stddef.h>
#include <string.h>

static NSString *SmsPusherString(const char *value) {
    if (value == NULL) {
        return @"";
    }
    NSString *string = [NSString stringWithUTF8String:value];
    return string == nil ? @"" : string;
}

@interface SmsPusherNotificationDelegate : NSObject <UNUserNotificationCenterDelegate>
@end

@implementation SmsPusherNotificationDelegate

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
       willPresentNotification:(UNNotification *)notification
         withCompletionHandler:(void (^)(UNNotificationPresentationOptions options))completionHandler {
    completionHandler(UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionList | UNNotificationPresentationOptionSound);
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
didReceiveNotificationResponse:(UNNotificationResponse *)response
         withCompletionHandler:(void (^)(void))completionHandler {
    @try {
        NSString *actionIdentifier = response.actionIdentifier;
        NSString *copyActionIdentifier = response.notification.request.content.userInfo[@"copyActionIdentifier"];
        NSString *verificationCode = response.notification.request.content.userInfo[@"verificationCode"];
        if (copyActionIdentifier.length > 0
            && [actionIdentifier isEqualToString:copyActionIdentifier]
            && verificationCode.length > 0) {
            NSPasteboard *pasteboard = [NSPasteboard generalPasteboard];
            [pasteboard clearContents];
            [pasteboard setString:verificationCode forType:NSPasteboardTypeString];
        }
    } @finally {
        completionHandler();
    }
}

@end

static SmsPusherNotificationDelegate *SmsPusherDelegate(void) {
    static SmsPusherNotificationDelegate *delegate = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        delegate = [[SmsPusherNotificationDelegate alloc] init];
    });
    return delegate;
}

static void SmsPusherConfigureNotificationCenter(
    UNUserNotificationCenter *center,
    NSString *categoryIdentifier,
    NSString *actionIdentifier,
    NSString *actionLabel
) {
    center.delegate = SmsPusherDelegate();
    if (categoryIdentifier.length == 0 || actionIdentifier.length == 0) {
        return;
    }
    NSString *title = actionLabel.length == 0 ? @"Copy verification code" : actionLabel;
    UNNotificationAction *copyAction = [UNNotificationAction actionWithIdentifier:actionIdentifier
                                                                             title:title
                                                                           options:UNNotificationActionOptionNone];
    UNNotificationCategory *category = [UNNotificationCategory categoryWithIdentifier:categoryIdentifier
                                                                              actions:@[copyAction]
                                                                    intentIdentifiers:@[]
                                                                              options:UNNotificationCategoryOptionNone];
    [center setNotificationCategories:[NSSet setWithObject:category]];
}

static void SmsPusherCopyError(char *buffer, size_t length, NSString *message) {
    if (buffer == NULL || length == 0) {
        return;
    }
    const char *text = [message UTF8String];
    if (text == NULL) {
        text = "unknown notification error";
    }
    size_t copy_length = strlen(text);
    if (copy_length >= length) {
        copy_length = length - 1;
    }
    memcpy(buffer, text, copy_length);
    buffer[copy_length] = '\0';
}

static bool SmsPusherWaitForAuthorization(UNUserNotificationCenter *center, char *error, size_t error_length) {
    __block UNAuthorizationStatus status = UNAuthorizationStatusNotDetermined;
    dispatch_semaphore_t settings_semaphore = dispatch_semaphore_create(0);
    [center getNotificationSettingsWithCompletionHandler:^(UNNotificationSettings *settings) {
        status = settings.authorizationStatus;
        dispatch_semaphore_signal(settings_semaphore);
    }];
    if (dispatch_semaphore_wait(settings_semaphore, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC)) != 0) {
        SmsPusherCopyError(error, error_length, @"Timed out reading notification authorization settings");
        return false;
    }

    if (status == UNAuthorizationStatusAuthorized || status == UNAuthorizationStatusProvisional) {
        return true;
    }

    if (status == UNAuthorizationStatusDenied) {
        SmsPusherCopyError(error, error_length, @"Notification authorization denied for SmsPusher");
        return false;
    }

    __block bool granted = false;
    __block NSString *request_error = nil;
    dispatch_semaphore_t request_semaphore = dispatch_semaphore_create(0);
    [center requestAuthorizationWithOptions:(UNAuthorizationOptionAlert | UNAuthorizationOptionSound)
                          completionHandler:^(BOOL did_grant, NSError *requestError) {
        granted = did_grant;
        request_error = requestError == nil ? nil : [requestError.localizedDescription copy];
        dispatch_semaphore_signal(request_semaphore);
    }];
    if (dispatch_semaphore_wait(request_semaphore, dispatch_time(DISPATCH_TIME_NOW, 30 * NSEC_PER_SEC)) != 0) {
        SmsPusherCopyError(error, error_length, @"Timed out requesting notification authorization");
        return false;
    }
    if (!granted) {
        SmsPusherCopyError(error, error_length, request_error == nil ? @"Notification authorization not granted" : request_error);
        return false;
    }
    return true;
}

void smspusher_request_user_notification_authorization(void) {
    @autoreleasepool {
        UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
        SmsPusherConfigureNotificationCenter(center, @"verification-code-message", @"copy_verification_code", @"Copy verification code");
        [center requestAuthorizationWithOptions:(UNAuthorizationOptionAlert | UNAuthorizationOptionSound)
                              completionHandler:^(BOOL granted, NSError *error) {
            if (!granted || error != nil) {
                NSString *message = error == nil ? @"Notification authorization not granted" : error.localizedDescription;
                NSLog(@"SmsPusher notification authorization warning: %@", message);
            }
        }];
    }
}

bool smspusher_show_user_notification(
    const char *identifier,
    const char *title,
    const char *body,
    const char *subtitle,
    const char *category_identifier,
    const char *action_identifier,
    const char *action_label,
    const char *verification_code,
    char *error,
    size_t error_length
) {
    @autoreleasepool {
        if ([[NSBundle mainBundle] bundleIdentifier] == nil) {
            SmsPusherCopyError(error, error_length, @"Missing bundle identifier for SmsPusher notifications");
            return false;
        }

        UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
        NSString *category_string = SmsPusherString(category_identifier);
        NSString *action_string = SmsPusherString(action_identifier);
        NSString *action_label_string = SmsPusherString(action_label);
        NSString *code_string = SmsPusherString(verification_code);
        SmsPusherConfigureNotificationCenter(center, category_string, action_string, action_label_string);
        if (!SmsPusherWaitForAuthorization(center, error, error_length)) {
            return false;
        }

        UNMutableNotificationContent *content = [[UNMutableNotificationContent alloc] init];
        content.title = SmsPusherString(title);
        content.body = SmsPusherString(body);
        content.sound = [UNNotificationSound defaultSound];
        NSString *subtitle_string = SmsPusherString(subtitle);
        if (subtitle_string.length > 0) {
            content.subtitle = subtitle_string;
        }
        if (category_string.length > 0 && action_string.length > 0 && code_string.length > 0) {
            content.categoryIdentifier = category_string;
            content.userInfo = @{
                @"copyActionIdentifier": action_string,
                @"verificationCode": code_string
            };
        }

        UNNotificationRequest *request = [UNNotificationRequest requestWithIdentifier:SmsPusherString(identifier)
                                                                              content:content
                                                                              trigger:nil];
        __block bool delivered = true;
        __block NSString *delivery_error = nil;
        dispatch_semaphore_t delivery_semaphore = dispatch_semaphore_create(0);
        [center addNotificationRequest:request
                 withCompletionHandler:^(NSError *requestError) {
            if (requestError != nil) {
                delivered = false;
                delivery_error = [requestError.localizedDescription copy];
            }
            dispatch_semaphore_signal(delivery_semaphore);
        }];
        if (dispatch_semaphore_wait(delivery_semaphore, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC)) != 0) {
            SmsPusherCopyError(error, error_length, @"Timed out delivering SmsPusher notification");
            return false;
        }
        if (!delivered) {
            SmsPusherCopyError(error, error_length, delivery_error == nil ? @"Could not deliver SmsPusher notification" : delivery_error);
            return false;
        }
        return true;
    }
}
