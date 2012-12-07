package com.keepit.search.langdetector;

import java.util.ArrayList;

/*
 * This code was taken from Cybozu langdetect (http://code.google.com/p/language-detection/) and modified by FOrtyTwo Inc.
 */

/**
 * {@link Language} is to store the detected language.
 * {@link Detector#getProbabilities()} returns an {@link ArrayList} of {@link Language}s.
 *
 * @see Detector#getProbabilities()
 * @author Nakatani Shuyo
 *
 */
public class Language {
    public String lang;
    public double prob;
    public Language(String lang, double prob) {
        this.lang = lang;
        this.prob = prob;
    }
    public String toString() {
        if (lang==null) return "";
        return lang + ":" + prob;
    }
}
