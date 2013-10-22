package com.keepit.common.net

import org.specs2.mutable.Specification
import com.keepit.common.service.ServiceUri

class NonOKResponseExceptionTest extends Specification {
  "NonOKResponseException" should {
    "have short message with DirectUrl" in {
      val exception = NonOKResponseException(
        DirectUrl("http://commons.apache.org/proper/commons-lang/javadocs/api-3.1/org/apache/commons/lang3/StringUtils.html#abbreviate(java.lang.String, int)"),
        FakeClientResponse("Abbreviates a String using ellipses. This will turn \"Now is the time for all good men\" into \"Now is the time for...\""),
        Some("""Specifically: If str is less than maxWidth characters long, return it.
              Else abbreviate it to (substring(str, 0, max-3) + "...").
              If maxWidth is less than 4, throw an IllegalArgumentException.
              In no case will it return a String of length greater than maxWidth.
               StringUtils.abbreviate(null, *)      = null
               StringUtils.abbreviate("", 4)        = ""
               StringUtils.abbreviate("abcdefg", 6) = "abc..."
               StringUtils.abbreviate("abcdefg", 7) = "abcdefg"
               StringUtils.abbreviate("abcdefg", 8) = "abcdefg"
               StringUtils.abbreviate("abcdefg", 4) = "a..."
               StringUtils.abbreviate("abcdefg", 3) = IllegalArgumentException"""))
      exception.getMessage() === "http://commons.apache.org/proper/commons-lang/j...->[Specifically: If str is les...] status:200 res [Abbreviates a String using ...]"
    }

    "have short message with ServiceUri" in {
      val exception = NonOKResponseException(
        new ServiceUri(null, null, -1, "/this/is/the/path/and/it/may/be/very/very/long/so/it/must/be/chopped/a/bit/if/you/know/what/i/mean"),
        FakeClientResponse("short response"),
        Some("short body"))
      exception.getMessage() === "/this/is/the/path/and/it/may/be/very/very/long/...->[short body] status:200 res [short response]"
    }
  }
}
