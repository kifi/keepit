package com.keepit.common

import play.api.http.HeaderNames._
import play.api.mvc.Request
import play.api.mvc.RequestHeader
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.healthcheck.HealthcheckError

package object net {

  class RichRequestHeader(request: RequestHeader) {
    def agent: String = agentOpt.getOrElse("UNKNOWN AGENT")
    def agentOpt: Option[String] = request.headers.get(USER_AGENT)
    
    def referer: String = refererOpt.getOrElse(throw new Exception("no referer header at: " + (request.headers.toMap mkString "\n")))
    def refererOpt: Option[String] = request.headers.get(REFERER)
  }
  
  implicit def requestHeaderToRichRequestHeader(request: RequestHeader) = new RichRequestHeader(request)
  
}