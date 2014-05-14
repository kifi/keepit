package com.keepit.common.net

import org.specs2.mutable.Specification
import com.keepit.common.service.ServiceUri
import com.keepit.common.zookeeper._
import com.keepit.common.service._

class NonOKResponseExceptionTest extends Specification {
  "NonOKResponseException" should {
    "have short message with DirectUrl" in {
      val exception = NonOKResponseException(
        DirectUrl("http://commons.apache.org/proper/commons-lang/javadocs/api-3.1/org/apache/commons/api-3.1/org/apache/commons/api-3.1/org/apache/commons/api-3.1/org/apache/commons/lang3/StringUtils.html#abbreviate(java.lang.String, int)"),
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
      exception.getMessage() === "[] ERR on http://commons.apache.org/proper/commons-lang/javadocs/api-3.1/org/apache/commons/api-3.1/org/apa... stat:200 - Abbreviates a String using ellipses. This will turn \"Now is the time for all good men\" into \"Now ...]"
    }

    "have short message with ServiceUri" in {
      val remoteService1 = RemoteService(null, ServiceStatus.UP, ServiceType.TEST_MODE)
      val instance = new ServiceInstance(Node("/node_00000001"), false, remoteService1)
      val exception = NonOKResponseException(
        new ServiceUri(instance, null, -1, "/this/is/the/path/and/it/may/be/very/very/long/so/it/must/be/chopped/a/bit/if/you/know/what/i/mean/this/is/the/path/and/it/may/be/very/very/long/so/it/must/be/chopped/a/bit/if/you/know/what/i/mean/this/is/the/path/and/it/may/be/very/very/long/so/it/must/be/chopped/a/bit/if/you/know/what/i/mean/this/is/the/path/and/it/may/be/very/very/long/so/it/must/be/chopped/a/bit/if/you/know/what/i/mean"),
        FakeClientResponse("short response"),
        Some("short body"))
      exception.getMessage() === "[TM1] ERR on TM1:/this/is/the/path/and/it/may/be/very/very/long/so/it/must/be/chopped/a/bit/if/you/know/what/i/mea... stat:200 - short response]"
    }
  }
}
