package org.jenkinsci.plugins.googleplayandroidpublisher;

import java.text.DecimalFormat;
import java.util.regex.Pattern;

public class Constants {

    /** File name pattern which expansion files must match. */
    static final Pattern OBB_FILE_REGEX =
            Pattern.compile("^(main|patch)\\.([0-9]+)\\.([._a-z0-9]+)\\.obb$", Pattern.CASE_INSENSITIVE);

    /** Expansion file type: main */
    static final String OBB_FILE_TYPE_MAIN = "main";

    /** Expansion file type: patch */
    static final String OBB_FILE_TYPE_PATCH = "patch";

    /** Formatter that only displays decimal places when necessary. */
    static final DecimalFormat PERCENTAGE_FORMATTER = new DecimalFormat("#.#");

    /** Allowed percentage values when doing a staged rollout to production. */
    static final double[] ROLLOUT_PERCENTAGES = { 0.5, 1, 5, 10, 20, 50, 100 };
    static final double DEFAULT_PERCENTAGE = 100;

}
