package com.keepit.search.langdetector;

/*
 * This code was taken from Cybozu langdetect (http://code.google.com/p/language-detection/) and modified by FOrtyTwo Inc.
 */

/**
 * @author Nakatani Shuyo
 */
enum ErrorCode {
    NoTextError, FormatError, FileLoadError, DuplicateLangError, NeedLoadProfileError, CantDetectError, CantOpenTrainData, TrainDataFormatError, InitParamError
}
