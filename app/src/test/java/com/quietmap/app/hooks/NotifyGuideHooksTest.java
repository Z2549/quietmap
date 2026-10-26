package com.quietmap.app.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 通知权限判定规则：空/null 不命中；大小写不敏感；
 * 包含 POST_NOTIFICATIONS 或 NOTIFICATION 即命中，其余权限不命中。
 */
public class NotifyGuideHooksTest {

    @Test
    public void notificationPermissionsMatch() {
        assertTrue(NotifyGuideHooks.isNotificationPermission(
                "android.permission.POST_NOTIFICATIONS"));
        assertTrue(NotifyGuideHooks.isNotificationPermission(
                "android.permission.NOTIFICATION"));
        assertTrue(NotifyGuideHooks.isNotificationPermission(
                "android.permission.POST_NOTIFICATIONS,android.permission.CAMERA"));
    }

    @Test
    public void nonNotificationPermissionsDoNotMatch() {
        assertFalse(NotifyGuideHooks.isNotificationPermission(
                "android.permission.ACCESS_FINE_LOCATION"));
        assertFalse(NotifyGuideHooks.isNotificationPermission(
                "android.permission.ACCESS_COARSE_LOCATION"));
        assertFalse(NotifyGuideHooks.isNotificationPermission(
                "android.permission.CAMERA"));
        assertFalse(NotifyGuideHooks.isNotificationPermission(
                "android.permission.READ_PHONE_STATE"));
        assertFalse(NotifyGuideHooks.isNotificationPermission(
                "android.permission.WRITE_EXTERNAL_STORAGE"));
        assertFalse(NotifyGuideHooks.isNotificationPermission(
                "android.permission.BLUETOOTH_CONNECT"));
    }

    @Test
    public void emptyOrNullNeverMatches() {
        assertFalse(NotifyGuideHooks.isNotificationPermission(null));
        assertFalse(NotifyGuideHooks.isNotificationPermission(""));
    }

    @Test
    public void matchIsCaseInsensitive() {
        assertTrue(NotifyGuideHooks.isNotificationPermission(
                "android.permission.post_notifications"));
        assertTrue(NotifyGuideHooks.isNotificationPermission(
                "android.permission.notification"));
    }
}
