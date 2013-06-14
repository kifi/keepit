package com.keepit.search.langdetector;

/*
 * This code was taken from Cybozu langdetect (http://code.google.com/p/language-detection/) and modified by FOrtyTwo Inc.
 */

/**
 * @author Nakatani Shuyo
 *
 */
public class LangDetectException extends Exception {
    private static final long serialVersionUID = 1L;
    private ErrorCode code;


    /**
     * @param code
     * @param message
     */
    public LangDetectException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * @return the error code
     */
    public ErrorCode getCode() {
        return code;
    }
}
