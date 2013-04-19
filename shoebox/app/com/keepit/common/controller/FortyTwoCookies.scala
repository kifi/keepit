package com.keepit.common.controller

import com.keepit.common.db._
import com.keepit.model._

import play.api._
import play.api.Play.current
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.http.ContentTypes
import play.api.libs.json.JsValue
import play.api.mvc._
import play.api.libs.iteratee._
import play.api.libs.iteratee.Input._
import play.api.libs.iteratee.Parsing._
import play.api.libs.json._
import play.api.mvc.Results.InternalServerError

object FortyTwoCookies {

  object ImpersonateCookie extends CookieBaker[Option[ExternalId[User]]] {
    val COOKIE_NAME = "impersonating"
    val emptyCookie = None
    override val isSigned = true
    override val secure = false
    override val maxAge = None
    override val httpOnly = true
    override val domain = current.configuration.getString("session.domain")
    def deserialize(data: Map[String, String]) = data.get(COOKIE_NAME).map(ExternalId[User](_))
    def serialize(data: Option[ExternalId[User]]) = data.map(id => Map(COOKIE_NAME -> id.id.toString())).getOrElse(Map.empty)
  }

  object KifiInstallationCookie extends CookieBaker[Option[ExternalId[KifiInstallation]]] {
    val COOKIE_NAME = "installation"
    val emptyCookie = None
    override val isSigned = true
    override val secure = false
    override val domain = current.configuration.getString("session.domain")
    def deserialize(data: Map[String, String]) = data.get(COOKIE_NAME).map(ExternalId[KifiInstallation](_))
    def serialize(data: Option[ExternalId[KifiInstallation]]) = data.map(id => Map(COOKIE_NAME -> id.id.toString())).getOrElse(Map.empty)
  }
}
