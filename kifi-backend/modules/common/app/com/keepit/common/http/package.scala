package com.keepit.common

import com.keepit.common.net.UserAgent
import play.api.mvc._
import play.api.http.HeaderNames.USER_AGENT

package object http {

  implicit class PimpedResult[A](request: Request[A]) {
    def userAgentOpt: Option[UserAgent] = request.headers.get(USER_AGENT).map { agentString =>
      UserAgent.fromString(agentString)
    }
  }
}
