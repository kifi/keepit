package com.keepit.common

import play.api.http.HeaderNames.{USER_AGENT, REFERER}
import play.api.mvc.RequestHeader

package object net {

  implicit class RichRequestHeader(request: RequestHeader) {
    lazy val agent: String = agentOpt.getOrElse("UNKNOWN AGENT")
    lazy val agentOpt: Option[String] = request.headers.get(USER_AGENT)

    lazy val referer: String = refererOpt.getOrElse(throw new Exception("no referer header at: " + (request.headers.toMap mkString "\n")))
    lazy val refererOpt: Option[String] = request.headers.get(REFERER)
  }

}

