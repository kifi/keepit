package com.keepit.social

import com.google.inject.Binder
import com.keepit.common.social.{ LinkedInSocialGraph, TwitterSocialGraph, FacebookSocialGraph }

import scala.concurrent.Future
import scala.util.Try

import com.keepit.model.{ UserValueName, SocialUserInfo }

import net.codingwell.scalaguice.{ ScalaMultibinder, ScalaModule }

import play.api.libs.json.JsValue

import securesocial.core.{ IdentityId, OAuth2Settings }
import com.keepit.common.mail.EmailAddress

/**
 * A generic social graph trait for a particular social network, e.g. Facebook, Twitter, LinkedIn
 */
trait SocialGraph {
  val networkType: SocialNetworkType
  def fetchSocialUserRawInfo(socialUserInfo: SocialUserInfo): Option[SocialUserRawInfo]
  def extractEmails(parentJson: JsValue): Seq[EmailAddress]
  def extractFriends(parentJson: JsValue): Seq[SocialUserInfo]
  def updateSocialUserInfo(sui: SocialUserInfo, json: JsValue): SocialUserInfo
  def revokePermissions(socialUserInfo: SocialUserInfo): Future[Unit]
  def extractUserValues(json: JsValue): Map[UserValueName, String]
  def vetJsAccessToken(settings: OAuth2Settings, json: JsValue): Try[IdentityId]
}

trait SocialGraphModule extends ScalaModule {
  def bindAllSocialGraphs(binder: Binder) = {
    val socialGraphBinder = ScalaMultibinder.newSetBinder[SocialGraph](binder)
    socialGraphBinder.addBinding.to[FacebookSocialGraph]
    socialGraphBinder.addBinding.to[TwitterSocialGraph]
    socialGraphBinder.addBinding.to[LinkedInSocialGraph]
  }
}