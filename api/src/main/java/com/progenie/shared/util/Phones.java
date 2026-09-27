package com.progenie.shared.util;

/** Indian mobile numbers are stored as typed (10 digits, or +91 / 91 followed by 10 digits). */
public final class Phones {

    private Phones() {
    }

    /** The 10-digit national number, or null if the value is not a phone number. */
    public static String national(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("[\\s-]", "");
        if (digits.startsWith("+")) {
            digits = digits.substring(1);
        }
        if (!digits.matches("[0-9]{10,14}")) {
            return null;
        }
        if (digits.length() == 12 && digits.startsWith("91")) {
            return digits.substring(2);
        }
        if (digits.length() == 11 && digits.startsWith("0")) {
            return digits.substring(1);
        }
        return digits.length() == 10 ? digits : null;
    }

    /** 91XXXXXXXXXX, the format SMS and WhatsApp providers expect. */
    public static String e164Digits(String value) {
        String national = national(value);
        return national == null ? null : "91" + national;
    }

    public static boolean looksLikePhone(String value) {
        return national(value) != null;
    }
}
