package com.quietmap.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * 通知权限判定规则：只认已知通知权限的精确名字（POST_NOTIFICATIONS 与旧的
 * NOTIFICATION / NOTIFICATION_*），大小写不敏感、忽略首尾空白；
 * ACCESS_NOTIFICATION_POLICY 与复合权限串不命中；String[]/List/Set 任一元素
 * 命中即命中；未知类型 fail-open（不拦截）。
 * JSON 改写：复制后把 permissionType 换成 location，不改入参。
 */
public class NotifyGuideHooksTest {

    @Test
    public void postNotificationsPermissionMatches() {
        assertTrue(NotifyGuideHooks.isNotificationPermission(
                "android.permission.POST_NOTIFICATIONS"));
        assertTrue("大小写不敏感", NotifyGuideHooks.isNotificationPermission(
                "android.permission.post_notifications"));
        assertTrue("忽略首尾空白", NotifyGuideHooks.isNotificationPermission(
                "  android.permission.POST_NOTIFICATIONS  "));
        assertTrue("简单名也兼容", NotifyGuideHooks.isNotificationPermission(
                "POST_NOTIFICATIONS"));
    }

    @Test
    public void legacyNotificationConstantsMatch() {
        assertTrue(NotifyGuideHooks.isNotificationPermission(
                "android.permission.NOTIFICATION"));
        assertTrue(NotifyGuideHooks.isNotificationPermission("NOTIFICATION"));
        assertTrue("旧 NOTIFICATION_* 常量族",
                NotifyGuideHooks.isNotificationPermission(
                        "android.permission.NOTIFICATION_LISTENER"));
        assertTrue(NotifyGuideHooks.isNotificationPermission(
                "notification_listener"));
    }

    @Test
    public void accessNotificationPolicyDoesNotMatch() {
        assertFalse("勿扰策略访问权限不是通知发布权限",
                NotifyGuideHooks.isNotificationPermission(
                        "android.permission.ACCESS_NOTIFICATION_POLICY"));
        assertFalse(NotifyGuideHooks.isNotificationPermission(
                "ACCESS_NOTIFICATION_POLICY"));
        assertFalse(NotifyGuideHooks.isNotificationPermission(
                "android.permission.access_notification_policy"));
    }

    @Test
    public void compositePermissionStringsDoNotMatch() {
        assertFalse("逗号分隔的复合串不能当成单一通知权限",
                NotifyGuideHooks.isNotificationPermission(
                        "android.permission.POST_NOTIFICATIONS,"
                                + "android.permission.CAMERA"));
        assertFalse(NotifyGuideHooks.isNotificationPermission(
                "android.permission.POST_NOTIFICATIONS;android.permission.CAMERA"));
        assertFalse(NotifyGuideHooks.isNotificationPermission(
                "android.permission.POST_NOTIFICATIONS|android.permission.CAMERA"));
        assertFalse(NotifyGuideHooks.isNotificationPermission(
                "android.permission.POST_NOTIFICATIONS android.permission.CAMERA"));
        assertFalse(NotifyGuideHooks.isNotificationPermission(
                "android.permission.NOTIFICATION,android.permission.CAMERA"));
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
        assertFalse("仅名字包含 NOTIFICATION 不足以命中",
                NotifyGuideHooks.isNotificationPermission(
                        "com.example.permission.PUSH_NOTIFICATION"));
    }

    @Test
    public void emptyOrNullNeverMatches() {
        assertFalse(NotifyGuideHooks.isNotificationPermission(null));
        assertFalse(NotifyGuideHooks.isNotificationPermission(""));
        assertFalse(NotifyGuideHooks.isNotificationPermission("   "));
    }

    @Test
    public void arrayListAndSetAreSupported() {
        assertTrue(NotifyGuideHooks.isNotificationPermission(new String[]{
                "android.permission.CAMERA",
                "android.permission.POST_NOTIFICATIONS"}));
        List<String> list = Arrays.asList(
                "android.permission.CAMERA", "android.permission.NOTIFICATION");
        assertTrue(NotifyGuideHooks.isNotificationPermission(list));
        assertTrue(NotifyGuideHooks.isNotificationPermission(
                new HashSet<>(Collections.singletonList(
                        "android.permission.POST_NOTIFICATIONS"))));
        assertFalse("数组中没有通知权限不命中",
                NotifyGuideHooks.isNotificationPermission(
                        new String[]{"android.permission.CAMERA"}));
        assertFalse(NotifyGuideHooks.isNotificationPermission(
                new String[0]));
        assertFalse(NotifyGuideHooks.isNotificationPermission(
                Collections.emptyList()));
        assertFalse(NotifyGuideHooks.isNotificationPermission(
                Collections.emptySet()));
        assertFalse("集合中的复合串不命中",
                NotifyGuideHooks.isNotificationPermission(
                        Collections.singletonList(
                                "android.permission.POST_NOTIFICATIONS,"
                                        + "android.permission.CAMERA")));
    }

    @Test
    public void unknownTypesFailOpen() {
        assertFalse(NotifyGuideHooks.isNotificationPermission(new Object()));
        assertFalse(NotifyGuideHooks.isNotificationPermission(Integer.valueOf(1)));
        assertFalse(NotifyGuideHooks.isNotificationPermission(new String[]{"x", null}));
    }

    @Test
    public void singleNotificationPermissionIsRecognized() {
        assertTrue(NotifyGuideHooks.isSingleNotificationPermission(
                "android.permission.POST_NOTIFICATIONS"));
        assertTrue(NotifyGuideHooks.isSingleNotificationPermission(
                "  android.permission.NOTIFICATION  "));
        assertFalse("勿扰策略权限不能改写",
                NotifyGuideHooks.isSingleNotificationPermission(
                        "android.permission.ACCESS_NOTIFICATION_POLICY"));
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
        assertFalse("勿扰策略权限不能改写",
                NotifyGuideHooks.shouldRewritePermission(
                        "android.permission.ACCESS_NOTIFICATION_POLICY", true));
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
