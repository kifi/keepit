package com.keepit.eliza.model

import java.util.UUID

case class MalformedThreadAccessToken() extends Exception()

case class ThreadAccessToken(token: String) {
  if (!ThreadAccessToken.IDPattern.pattern.matcher(token).matches()) {
    throw new Exception("thread access token [%s] does not match id pattern".format(token))
  }
  override def toString = token
}

object ThreadAccessToken {

  val IDPattern = "[0-9a-f]{32}".r

  def apply(): ThreadAccessToken = ThreadAccessToken(UUID.randomUUID.toString.replace("-", ""))
}
