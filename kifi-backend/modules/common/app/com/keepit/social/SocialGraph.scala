package com.keepit.social

import scala.concurrent.Future

import com.keepit.model.SocialUserInfo

import net.codingwell.scalaguice.ScalaModule

import play.api.libs.json.JsValue

import securesocial.core.{IdentityId, IdentityProvider}

/**
 * A generic social graph trait for a particular social network, e.g. Facebook, Twitter, LinkedIn
 */
trait SocialGraph {
  val networkType: SocialNetworkType
  def fetchSocialUserRawInfo(socialUserInfo: SocialUserInfo): Option[SocialUserRawInfo]
  def extractEmails(parentJson: JsValue): Seq[String]
  def extractFriends(parentJson: JsValue): Seq[SocialUserInfo]
  def updateSocialUserInfo(sui: SocialUserInfo, json: JsValue): SocialUserInfo
  def revokePermissions(socialUserInfo: SocialUserInfo): Future[Unit]
  def extractUserValues(json: JsValue): Map[String, String]
  def vetJsAccessToken(provider: IdentityProvider, json: JsValue): Future[IdentityId]
}

trait SocialGraphModule extends ScalaModule
