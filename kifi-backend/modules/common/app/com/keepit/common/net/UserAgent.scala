package com.keepit.common.net

import com.keepit.common.logging.Logging
import net.sf.uadetector.service.UADetectorServiceFactory
import net.sf.uadetector.{ ReadableUserAgent => SFUserAgent }
import play.api.mvc.{ Request => PlayRequest }

case class UserAgent(
    userAgent: String,
    name: String,
    operatingSystemFamily: String,
    operatingSystemName: String,
    typeName: String,
    version: String) {
  lazy val isKifiIphoneApp: Boolean = typeName == UserAgent.KifiIphoneAppTypeName
  lazy val isKifiAndroidApp: Boolean = operatingSystemFamily == "Android" // TODO: use a custom User-Agent header in our Android app
  lazy val isIphone: Boolean = (operatingSystemFamily == "iOS" && userAgent.contains("CPU iPhone OS")) || isKifiIphoneApp
  lazy val isAndroid: Boolean = operatingSystemFamily == "Android" || isKifiAndroidApp
  lazy val isMobile: Boolean = UserAgent.MobileOses.contains(operatingSystemFamily) || isKifiIphoneApp || isKifiAndroidApp
  lazy val isMobileWeb: Boolean = UserAgent.MobileOses.contains(operatingSystemFamily)
  lazy val isMobileApp: Boolean = isKifiIphoneApp || isKifiAndroidApp
  lazy val screenCanFitWebApp: Boolean = !isMobileWeb // || UserAgent.TabletIndicators.exists(userAgent.contains(_))  // TODO: let people use web app on tablet
  lazy val canRunExtensionIfUpToDate: Boolean = !isMobile && UserAgent.ExtensionBrowserNames.contains(name)
  lazy val isOldIE: Boolean = name == "IE" && (try { version.toDouble.toInt } catch { case _: NumberFormatException => Double.MaxValue }) < 10
}

object UserAgent extends Logging {

  val KifiIphoneAppTypeName = "kifi iphone app"

  private val MobileOses = Set("Android", "iOS", "Bada", "DangerOS", "Firefox OS", "Mac OS", "Palm OS", "BlackBerry OS", "Symbian OS", "webOS")
  private val TabletIndicators = Set("iPad", "Tablet")
  private val ExtensionBrowserNames = Set("Chrome", "Firefox")

  private val MAX_USER_AGENT_LENGTH = 512
  lazy val parser = UADetectorServiceFactory.getResourceModuleParser()
  lazy val iosAppRe = """^(iKeefee)/(\d+\.\d+)(\.\d+) \(Device-Type: (.+), OS: (iOS) (.+)\)$""".r("appName", "appVersion", "buildSuffix", "device", "os", "osVersion")

  private def normalize(str: String): String = if (str == "unknown") "" else str
  private def normalizeChrome(str: String): String = if (str == "Chromium") "Chrome" else str

  def apply(request: PlayRequest[_]): UserAgent = fromString(request.headers.get("user-agent").getOrElse(""))
  def apply(request: String): UserAgent = fromString(request)

  private def fromString(userAgent: String): UserAgent = {
    userAgent match {
      case iosAppRe(appName, appVersion, buildSuffix, device, os, osVersion) =>
        UserAgent(userAgent, appName, os, device, KifiIphoneAppTypeName, appVersion)
      case _ =>
        val agent: SFUserAgent = parser.parse(userAgent)
        UserAgent(trim(userAgent),
          normalizeChrome(normalize(agent.getName)),
          normalize(agent.getOperatingSystem.getFamilyName),
          normalize(agent.getOperatingSystem.getName),
          normalize(agent.getTypeName),
          normalize(agent.getVersionNumber.toVersionString))
    }
  }

  private def trim(str: String) = {
    if (str.length > MAX_USER_AGENT_LENGTH) {
      log.warn(s"trunking user agent string since its too long: $str")
      str.substring(0, MAX_USER_AGENT_LENGTH - 3) + "..."
    } else {
      str
    }
  }
}
