package com.keepit.common.net

import org.specs2.mutable.Specification

class UserAgentTest extends Specification {
  "User Agent" should {
    "don't parse old agent" in {
      val str = "Chrome/26.0.1410.65"
      val agent = UserAgent.fromString(str)
      agent === UserAgent(str, "", "", "", "", "")
      agent.isMobile === false
      agent.canRunExtensionIfUpToDate === false
      agent.isKifiIphoneApp === false
      agent.isKifiAndroidApp === false
      agent.isIphone === false
      agent.isAndroid === false
      agent.screenCanFitWebApp === true
      agent.isOldIE === false
    }
    "parse browser versions FF Mac" in {
      val str = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.8; rv:20.0) Gecko/20100101 Firefox/20.0"
      val agent = UserAgent.fromString(str)
      agent === UserAgent(str, "Firefox", "OS X", "OS X 10.8 Mountain Lion", "Browser", "20.0")
      agent.isMobile === false
      agent.canRunExtensionIfUpToDate === true
      agent.isKifiIphoneApp === false
      agent.isKifiAndroidApp === false
      agent.isIphone === false
      agent.isAndroid === false
      agent.screenCanFitWebApp === true
      agent.isOldIE === false
    }
    "parse browser versions Chrome Linux" in {
      val str = "Mozilla/5.0 (X11; CrOS armv7l 2913.260.0) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.99 Safari/537.11"
      val agent = UserAgent.fromString(str)
      agent === UserAgent(str, "Chrome", "Linux", "Chrome OS", "Browser", "23.0.1271.99")
      agent.isMobile === false
      agent.canRunExtensionIfUpToDate === true
      agent.isKifiIphoneApp === false
      agent.isKifiAndroidApp === false
      agent.isIphone === false
      agent.isAndroid === false
      agent.screenCanFitWebApp === true
      agent.isOldIE === false
    }
    "parse browser versions iPhone app" in {
      val str = "iKeefee/1.0.12823 (Device-Type: iPhone, OS: iOS 7.0.6)"
      val agent = UserAgent.fromString(str)
      agent === UserAgent(str, "iKeefee", "iOS", "iPhone", UserAgent.KifiIphoneAppTypeName, "1.0")
      agent.isMobile === true
      agent.isKifiIphoneApp === true
      agent.isKifiAndroidApp === false
      agent.isIphone === true
      agent.isAndroid === false
      agent.screenCanFitWebApp === false
      agent.isOldIE === false
    }
    "parse browser versions Android app" in {
      val str = "??? Android ???" // TODO: insert actual Android app user agent string
      val agent = UserAgent.fromString(str)
      agent === UserAgent(str, "", "Android", "Android", "", "")
      agent.isMobile === true
      agent.isKifiIphoneApp === false
      agent.isKifiAndroidApp === true
      agent.isIphone === false
      agent.isAndroid === true
      agent.screenCanFitWebApp === false
      agent.isOldIE === false
    }
    "parse browser versions Chromium Linux" in {
      val str = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/535.19 (KHTML, like Gecko) Ubuntu/11.10 Chromium/18.0.1025.142 Chrome/18.0.1025.142 Safari/535.19"
      val agent = UserAgent.fromString(str)
      val manual = UserAgent(str, "Chrome", "Linux", "Linux (Ubuntu)", "Browser", "18.0.1025.142")
      agent.name === manual.name
      agent === manual
      agent.isMobile === false
      agent.canRunExtensionIfUpToDate === true
      agent.isKifiIphoneApp === false
      agent.isKifiAndroidApp === false
      agent.isIphone === false
      agent.isAndroid === false
      agent.screenCanFitWebApp === true
      agent.isOldIE === false
    }
    "parse browser versions IE < 9 on Windows" in {
      val str = "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 6.1; WOW64; Trident/5.0; SLCC2; .NET CLR 2.0.50727; .NET CLR 3.5.30729; .NET CLR 3.0.30729; Media Center PC 6.0; .NET4.0C; .NET4.0E; InfoPath.3; Creative AutoUpdate v1.40.02)"
      val agent = UserAgent.fromString(str)
      agent === UserAgent(str, "IE", "Windows", "Windows 7", "Browser", "7.0")
      agent.isMobile === false
      agent.canRunExtensionIfUpToDate === false
      agent.isKifiIphoneApp === false
      agent.isKifiAndroidApp === false
      agent.isIphone === false
      agent.isAndroid === false
      agent.screenCanFitWebApp === true
      agent.isOldIE === true
    }
    "parse browser version IE 9 on Windows" in {
      val str = "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; Win64; x64; Trident/5.0; .NET CLR 3.5.30729; .NET CLR 3.0.30729; .NET CLR 2.0.50727; Media Center PC 6.0)"
      val agent = UserAgent.fromString(str)
      agent === UserAgent(str, "IE", "Windows", "Windows 7", "Browser", "9.0")
      agent.isMobile === false
      agent.canRunExtensionIfUpToDate === false
      agent.isKifiIphoneApp === false
      agent.isKifiAndroidApp === false
      agent.isIphone === false
      agent.isAndroid === false
      agent.screenCanFitWebApp === true
      agent.isOldIE === true
    }
    "parse browser version IE 10 on Windows" in {
      val str = "Mozilla/5.0 (compatible; MSIE 10.0; Windows NT 6.1; Trident/4.0; InfoPath.2; SV1; .NET CLR 2.0.50727; WOW64)"
      val agent = UserAgent.fromString(str)
      agent === UserAgent(str, "IE", "Windows", "Windows 7", "Browser", "10.0")
      agent.isMobile === false
      agent.canRunExtensionIfUpToDate === false
      agent.isKifiIphoneApp === false
      agent.isKifiAndroidApp === false
      agent.isIphone === false
      agent.isAndroid === false
      agent.screenCanFitWebApp === true
      agent.isOldIE === false
    }
    "parse browser version IE 11 on Windows" in {
      val str = "Mozilla/5.0 (Windows NT 6.3; Trident/7.0; rv:11.0) like Gecko"
      val agent = UserAgent.fromString(str)
      agent === UserAgent(str, "IE", "Windows", "Windows 8.1", "Browser", "11.0")
      agent.isMobile === false
      agent.canRunExtensionIfUpToDate === false
      agent.isKifiIphoneApp === false
      agent.isKifiAndroidApp === false
      agent.isIphone === false
      agent.isAndroid === false
      agent.screenCanFitWebApp === true
      agent.isOldIE === false
    }
    "parse browser versions Chrome on iPhone" in {
      val str = "Mozilla/5.0 (iPhone; U; CPU iPhone OS 5_1_1 like Mac OS X; en) AppleWebKit/534.46.0 (KHTML, like Gecko) CriOS/19.0.1084.60 Mobile/9B206 Safari/7534.48.3"
      val agent = UserAgent.fromString(str)
      agent === UserAgent(str, "Chrome Mobile", "iOS", "iOS 5", "Mobile Browser", "19.0.1084.60")
      agent.isMobile === true
      agent.canRunExtensionIfUpToDate === false
      agent.isKifiIphoneApp === false
      agent.isKifiAndroidApp === false
      agent.isIphone === true
      agent.isAndroid === false
      agent.screenCanFitWebApp === false
      agent.isOldIE === false
    }
    "parse browser versions Safari on iPhone" in {
      val str = "Mozilla/5.0 (iPhone; U; CPU iPhone OS 4_0 like Mac OS X; en-us) AppleWebKit/532.9 (KHTML, like Gecko) Version/4.0.5 Mobile/8A293 Safari/6531.22.7"
      val agent = UserAgent.fromString(str)
      agent === UserAgent(str, "Mobile Safari", "iOS", "iOS 4", "Mobile Browser", "4.0.5")
      agent.isMobile === true
      agent.canRunExtensionIfUpToDate === false
      agent.isKifiIphoneApp === false
      agent.isKifiAndroidApp === false
      agent.isIphone === true
      agent.isAndroid === false
      agent.screenCanFitWebApp === false
      agent.isOldIE === false
    }
    "parse browser versions Safari on iPad" in {
      val str = "Mozilla/5.0 (iPad; CPU OS 7_0_4 like Mac OS X) AppleWebKit/537.51.1 (KHTML, like Gecko) Version/7.0 Mobile/11B554a Safari/9537.53"
      val agent = UserAgent.fromString(str)
      agent === UserAgent(str, "Mobile Safari", "iOS", "iOS 7", "Mobile Browser", "7.0")
      agent.isMobile === true
      agent.canRunExtensionIfUpToDate === false
      agent.isKifiIphoneApp === false
      agent.isKifiAndroidApp === false
      agent.isIphone === false
      agent.isAndroid === false
      agent.screenCanFitWebApp === false // TODO: make true
      agent.isOldIE === false
    }
    "parse browser versions Firefox on Android Tablet" in {
      val str = "Mozilla/5.0 (Android; Tablet; rv:28.0) Gecko/28.0 Firefox/28.0"
      val agent = UserAgent.fromString(str)
      agent === UserAgent(str, "Firefox", "Android", "Android", "Browser", "28.0")
      agent.isMobile === true
      agent.canRunExtensionIfUpToDate === false
      agent.isKifiIphoneApp === false
      agent.isKifiAndroidApp === true // TODO: make false
      agent.isIphone === false
      agent.isAndroid === true
      agent.screenCanFitWebApp === false // TODO: make true
      agent.isOldIE === false
    }
  }
}
