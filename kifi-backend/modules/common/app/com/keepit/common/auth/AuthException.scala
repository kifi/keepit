package com.keepit.common.auth

class AuthException(msg: String, cause: Throwable) extends Throwable(msg, cause) {
  def this(msg: String) = this(msg, null)
}
