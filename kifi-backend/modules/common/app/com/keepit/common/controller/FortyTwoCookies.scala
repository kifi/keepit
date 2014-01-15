package com.keepit.common.controller

import com.keepit.common.db._
import com.keepit.model._

import play.api.mvc._

object FortyTwoCookies {

  class ImpersonateCookie(override val domain: Option[String]) extends CookieBaker[Option[ExternalId[User]]] {
    val COOKIE_NAME = "impersonating"
    val emptyCookie = None
    override val isSigned = true
    override val secure = false
    override val maxAge = None
    override val httpOnly = true
    def deserialize(data: Map[String, String]) = data.get(COOKIE_NAME).map(ExternalId[User](_))
    def serialize(data: Option[ExternalId[User]]) = data.map(id => Map(COOKIE_NAME -> id.id.toString())).getOrElse(Map.empty)
  }

  class KifiInstallationCookie(override val domain: Option[String]) extends CookieBaker[Option[ExternalId[KifiInstallation]]] {
    val COOKIE_NAME = "installation"
    val emptyCookie = None
    override val isSigned = true
    override val secure = false
    def deserialize(data: Map[String, String]) = data.get(COOKIE_NAME).map(ExternalId[KifiInstallation](_))
    def serialize(data: Option[ExternalId[KifiInstallation]]) = data.map(id => Map(COOKIE_NAME -> id.id.toString())).getOrElse(Map.empty)
  }
}
