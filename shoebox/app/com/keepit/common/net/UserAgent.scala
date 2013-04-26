package com.keepit.common.net

import com.keepit.common.logging.Logging

case class UserAgent(val userAgent: String) {//here we'll have some smartness about parsing the string
  if(userAgent.length > UserAgent.MAX_USER_AGENT_LENGTH) throw new Exception("trunking user agent string since its too long: %s".format(userAgent))
}

object UserAgent extends Logging {

  val MAX_USER_AGENT_LENGTH = 512

  def fromString(userAgent: String): UserAgent = if(userAgent.length >  MAX_USER_AGENT_LENGTH) {
      log.warn("trunking user agent string since its too long: %s".format(userAgent))
      new UserAgent(userAgent.substring(0, MAX_USER_AGENT_LENGTH - 3) + "...")
    } else {
      new UserAgent(userAgent)
    }
}
