package com.logguardian.util;

import com.github.f4b6a3.uuid.UuidCreator;
import org.apache.commons.lang3.StringUtils;

public final class Utils {

    private Utils() {
    }

    public static boolean checkIfJson(String message) {
        if (StringUtils.isEmpty(message)) {
            return false;
        }

        String trimmed = message.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}");
    }

    public static String generateIncidentId(String fingerprint, String sourceId) {
        StringBuilder builder = new StringBuilder();
        builder.append(fingerprint);
        builder.append(sourceId);
        return UuidCreator.getNameBasedSha1(builder.toString()).toString();
    }
}
