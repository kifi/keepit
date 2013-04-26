package com.keepit.common.net

import com.keepit.common.logging.Logging
import net.sf.uadetector.service.UADetectorServiceFactory
import net.sf.uadetector.{UserAgent => SFUserAgent, UserAgentStringParser}


case class UserAgent(
  userAgent: String,
  name: String,
  operatingSystemFamily: String,
  operatingSystemName: String,
  typeName: String,
  version: String)

object UserAgent extends Logging {

  private val MAX_USER_AGENT_LENGTH = 512
  private lazy val parser = UADetectorServiceFactory.getResourceModuleParser()

  def fromString(userAgent: String): UserAgent = {
    val agent: SFUserAgent = parser.parse(userAgent)
    UserAgent(trim(userAgent),
      agent.getName,
      agent.getOperatingSystem.getFamilyName,
      agent.getOperatingSystem.getName,
      agent.getTypeName,
      agent.getVersionNumber.toVersionString)
  }

  private def trim(str: String) = if(str.length > MAX_USER_AGENT_LENGTH) {
      log.warn(s"trunking user agent string since its too long: $str")
      str.substring(0, MAX_USER_AGENT_LENGTH - 3) + "..."
    } else {
      str
    }
}
