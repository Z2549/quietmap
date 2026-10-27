package com.quietmap.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 通知权限判定规则：空/null 不命中；大小写不敏感；
 * 包含 POST_NOTIFICATIONS 或 NOTIFICATION 即命中，其余权限不命中。
 * JSON 改写：复制后把 permissionType 换成 location，不改入参。
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
        assertTrue(NotifyGuideHooks.isNotificationPermission(
                "Android.Permission.Post_Notifications"));
    }

    @Test
    public void singleNotificationPermissionIsRecognized() {
        assertTrue(NotifyGuideHooks.isSingleNotificationPermission(
                "android.permission.POST_NOTIFICATIONS"));
        assertTrue(NotifyGuideHooks.isSingleNotificationPermission(
                "  android.permission.NOTIFICATION  "));
    }

    @Test
    public void compositeOrOtherPermissionsAreNotSingleNotification() {
        assertFalse(NotifyGuideHooks.isSingleNotificationPermission(
                "android.permission.POST_NOTIFICATIONS,android.permission.CAMERA"));
        assertFalse(NotifyGuideHooks.isSingleNotificationPermission(
                "android.permission.POST_NOTIFICATIONS;android.permission.CAMERA"));
        assertFalse(NotifyGuideHooks.isSingleNotificationPermission(
                "android.permission.POST_NOTIFICATIONS|android.permission.CAMERA"));
        assertFalse(NotifyGuideHooks.isSingleNotificationPermission(
                "android.permission.POST_NOTIFICATIONS android.permission.CAMERA"));
        assertFalse(NotifyGuideHooks.isSingleNotificationPermission(
                "android.permission.CAMERA"));
        assertFalse(NotifyGuideHooks.isSingleNotificationPermission(null));
        assertFalse(NotifyGuideHooks.isSingleNotificationPermission(""));
    }

    @Test
    public void rewriteOnlyWhenSingleNotificationAndLocationGranted() {
        assertTrue(NotifyGuideHooks.shouldRewritePermission(
                "android.permission.POST_NOTIFICATIONS", true));
        assertFalse("定位未授予时改写会新增定位弹窗",
                NotifyGuideHooks.shouldRewritePermission(
                        "android.permission.POST_NOTIFICATIONS", false));
        assertFalse("复合权限串不能整体改写",
                NotifyGuideHooks.shouldRewritePermission(
                        "android.permission.POST_NOTIFICATIONS,android.permission.CAMERA",
                        true));
        assertFalse("非通知权限不改写",
                NotifyGuideHooks.shouldRewritePermission(
                        "android.permission.CAMERA", true));
    }

    @Test
    public void rewriteCopiesAndReplacesPermissionType() throws Exception {
        JSONObject original = new JSONObject()
                .put("permissionType", "android.permission.POST_NOTIFICATIONS")
                .put("scene", "home");
        JSONObject patched = NotifyGuideHooks.rewriteNotificationPermission(original);
        assertNotSame("必须复制，不能改入参", original, patched);
        assertEquals("location", patched.getString("permissionType"));
        assertEquals("home", patched.getString("scene"));
        assertEquals("入参保持原值", "android.permission.POST_NOTIFICATIONS",
                original.getString("permissionType"));
    }

    @Test
    public void rewriteHandlesNullAndKeepsOtherFields() throws Exception {
        assertNull(NotifyGuideHooks.rewriteNotificationPermission(null));
        JSONObject original = new JSONObject()
                .put("permissionType", "android.permission.NOTIFICATION")
                .put("retry", 3);
        JSONObject patched = NotifyGuideHooks.rewriteNotificationPermission(original);
        assertEquals(3, patched.getInt("retry"));
        assertEquals("location", patched.getString("permissionType"));
    }
}
