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

  def isMobile(): Boolean = false
  def isSupportedDesktop(): Boolean = UserAgent.SupportedDesktopBrowsers.contains(name)
}

object UserAgent extends Logging {

  val SupportedDesktopBrowsers = Set("Chrome", "Firefox")

  private val MAX_USER_AGENT_LENGTH = 512
  lazy val parser = UADetectorServiceFactory.getResourceModuleParser()
  lazy val iPhonePattern = """^(iKeefee)/(\d+\.\d+)(\.\d+) \(Device-Type: (.+), OS: (iOS) (.+)\)$""".r("appName", "appVersion", "buildSuffix", "device", "os", "osVersion")

  def fromString(userAgent: String): UserAgent = {
    def normalize(str: String) = if (str == "unknown") "" else str
    val agent: SFUserAgent = parser.parse(userAgent)
    UserAgent(trim(userAgent),
      normalize(agent.getName),
      normalize(agent.getOperatingSystem.getFamilyName),
      normalize(agent.getOperatingSystem.getName),
      normalize(agent.getTypeName),
      normalize(agent.getVersionNumber.toVersionString))
  }

  private def trim(str: String) = if(str.length > MAX_USER_AGENT_LENGTH) {
      log.warn(s"trunking user agent string since its too long: $str")
      str.substring(0, MAX_USER_AGENT_LENGTH - 3) + "..."
    } else {
      str
    }
}
