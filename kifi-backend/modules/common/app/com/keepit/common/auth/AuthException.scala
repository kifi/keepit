package com.keepit.common.auth

import play.api.libs.ws.WSResponse

class AuthException(msg: String, response: WSResponse, cause: Throwable) extends Exception(msg, cause) {
  def this(msg: String, response: WSResponse) = this(msg, response, null)
  def this(msg: String) = this(msg, null, null)
}
