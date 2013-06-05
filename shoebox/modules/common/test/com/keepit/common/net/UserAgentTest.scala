package com.keepit.common.net

import org.specs2.mutable.Specification

class UserAgentTest extends Specification {
  "User Agent" should {
    "don't parse old agent" in {
      val str = "Chrome/26.0.1410.65"
      UserAgent.fromString(str) === UserAgent(str, "", "" ,"", "", "")
    }
    "parse browser versions FF Mac" in {
      val str = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.8; rv:20.0) Gecko/20100101 Firefox/20.0"
      UserAgent.fromString(str) === UserAgent(str, "Firefox", "OS X" ,"OS X 10.8 Mountain Lion", "Browser", "20.0")
    }
    "parse browser versions Chrome Linux" in {
      val str = "Mozilla/5.0 (X11; CrOS armv7l 2913.260.0) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.99 Safari/537.11"
      UserAgent.fromString(str) === UserAgent(str, "Chrome", "Linux", "Chromium OS", "Browser", "23.0.1271.99")
    }
    "parse browser versions IE on Windows" in {
      val str = "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 6.1; WOW64; Trident/5.0; SLCC2; .NET CLR 2.0.50727; .NET CLR 3.5.30729; .NET CLR 3.0.30729; Media Center PC 6.0; .NET4.0C; .NET4.0E; InfoPath.3; Creative AutoUpdate v1.40.02)"
      UserAgent.fromString(str) === UserAgent(str, "IE", "Windows", "Windows 7", "Browser", "7.0")
    }
  }
}
