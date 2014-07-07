package com.keepit.common.net

import com.keepit.common.logging.Logging
import net.sf.uadetector.service.UADetectorServiceFactory
import net.sf.uadetector.{ReadableUserAgent => SFUserAgent}


case class UserAgent(
  userAgent: String,
  name: String,
  operatingSystemFamily: String,
  operatingSystemName: String,
  typeName: String,
  version: String) {
  lazy val isKifiIphoneApp: Boolean = typeName == UserAgent.KifiIphoneAppTypeName
  lazy val isKifiAndroidApp: Boolean = typeName == UserAgent.KifiAndroidAppTypeName
  lazy val isIphone: Boolean = (operatingSystemFamily == "iOS" && userAgent.contains("CPU iPhone OS")) || isKifiIphoneApp
  lazy val isMobile: Boolean = UserAgent.MobileOs.contains(operatingSystemFamily) || isKifiIphoneApp
  lazy val isWebsiteEnabled: Boolean = !isMobile || UserAgent.WebsiteEnabled.exists(userAgent.contains(_))
  lazy val isPreviewWebsiteEnabled: Boolean = !isMobile || UserAgent.PreviewWebsiteEnabled.exists(userAgent.contains(_))
  lazy val isSupportedDesktop: Boolean = {
    !isMobile && UserAgent.SupportedDesktopBrowsers.contains(name)
  }
}

object UserAgent extends Logging {

  val KifiIphoneAppTypeName = "kifi iphone app"
  val KifiAndroidAppTypeName = "kifi android app"

  val MobileOs = Set("Android", "iOS", "Bada", "DangerOS", "Firefox OS", "Mac OS", "Palm OS", "BlackBerry OS", "Symbian OS", "webOS")
  val WebsiteEnabled = Set()
  val PreviewWebsiteEnabled = Set("iPad", "Tablet")
  val SupportedDesktopBrowsers = Set("Chrome", "Firefox")

  private val MAX_USER_AGENT_LENGTH = 512
  lazy val parser = UADetectorServiceFactory.getResourceModuleParser()
  lazy val iPhonePattern = """^(iKeefee)/(\d+\.\d+)(\.\d+) \(Device-Type: (.+), OS: (iOS) (.+)\)$""".r("appName", "appVersion", "buildSuffix", "device", "os", "osVersion")

  private def normalize(str: String): String = if (str == "unknown") "" else str
  private def normalizeChrome(str: String): String = if (str == "Chromium") "Chrome" else str

  def fromString(userAgent: String): UserAgent = {
    val agent: SFUserAgent = parser.parse(userAgent)
    userAgent match {
      case iPhonePattern(appName, appVersion, buildSuffix, device, os, osVersion) =>
        UserAgent(userAgent, appName, os, device, KifiIphoneAppTypeName, appVersion)
      case _ =>
        UserAgent(trim(userAgent),
          normalizeChrome(normalize(agent.getName)),
          normalize(agent.getOperatingSystem.getFamilyName),
          normalize(agent.getOperatingSystem.getName),
          normalize(agent.getTypeName),
          normalize(agent.getVersionNumber.toVersionString))
    }
  }

  private def trim(str: String) = if(str.length > MAX_USER_AGENT_LENGTH) {
      log.warn(s"trunking user agent string since its too long: $str")
      str.substring(0, MAX_USER_AGENT_LENGTH - 3) + "..."
    } else {
      str
    }
}
