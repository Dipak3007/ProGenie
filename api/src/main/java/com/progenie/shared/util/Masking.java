package com.progenie.shared.util;

/** Hides most of a phone number or email address in logs and admin screens. */
public final class Masking {

    private Masking() {
    }

    /** 9825000005 → 98******05; +919825000005 → +9198******05. */
    public static String phone(String phone) {
        if (phone == null || phone.length() < 6) {
            return phone == null ? null : "****";
        }
        int keepStart = phone.length() - 8;
        return phone.substring(0, Math.max(2, keepStart)) + "******" + phone.substring(phone.length() - 2);
    }

    /** customer@example.com → c*******@example.com. */
    public static String email(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "****";
        }
        return email.charAt(0) + "*".repeat(Math.max(3, at - 1)) + email.substring(at);
    }

    /** Masks either kind of destination. */
    public static String destination(String value) {
        return value != null && value.contains("@") ? email(value) : phone(value);
    }
}
