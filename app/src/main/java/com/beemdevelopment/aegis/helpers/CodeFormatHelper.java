package com.beemdevelopment.aegis.helpers;

import com.beemdevelopment.aegis.Preferences;
import com.beemdevelopment.aegis.otp.OtpInfo;
import com.beemdevelopment.aegis.otp.SteamInfo;
import com.beemdevelopment.aegis.otp.YandexInfo;

public class CodeFormatHelper {
    public static final char HIDDEN_CHAR = '•';

    private CodeFormatHelper() {

    }

    /**
     * Groups the digits of the given code according to the given grouping setting. Codes
     * of types that aren't purely numeric (Steam, Yandex) are returned as-is.
     */
    public static String format(String code, OtpInfo info, Preferences.CodeGrouping grouping) {
        if (info instanceof SteamInfo || info instanceof YandexInfo) {
            return code;
        }

        return format(code, grouping);
    }

    public static String format(String code, Preferences.CodeGrouping grouping) {
        int groupSize;
        switch (grouping) {
            case NO_GROUPING:
                groupSize = code.length();
                break;
            case HALVES:
                groupSize = (code.length() / 2) + (code.length() % 2);
                break;
            default:
                groupSize = grouping.getValue();
                if (groupSize <= 0) {
                    throw new IllegalArgumentException("Code group size cannot be zero or negative");
                }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (i != 0 && i % groupSize == 0) {
                sb.append(" ");
            }
            sb.append(code.charAt(i));
        }

        return sb.toString();
    }

    /**
     * Replaces every non-whitespace character of a (formatted) code with a dot.
     */
    public static String hide(String formattedCode) {
        return formattedCode.replaceAll("\\S", Character.toString(HIDDEN_CHAR));
    }

    public static boolean isNumeric(String code) {
        if (code.isEmpty()) {
            return false;
        }

        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) < '0' || code.charAt(i) > '9') {
                return false;
            }
        }

        return true;
    }
}
