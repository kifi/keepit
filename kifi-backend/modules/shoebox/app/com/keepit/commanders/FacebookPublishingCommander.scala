package com.keepit.commanders

import java.net.URLEncoder

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{ AirbrakeNotifier, StackTrace }
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ HttpClient, DirectUrl, CallTimeouts }
import com.keepit.common.strings._
import com.keepit.model._
import com.keepit.social.SocialNetworks
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.util.{ Failure, Success }

class FacebookPublishingCommander @Inject() (
    experimentCommander: LocalUserExperimentCommander,
    db: Database,
    socialUserInfoRepo: SocialUserInfoRepo,
    userRepo: UserRepo,
    httpClient: HttpClient,
    airbrake: AirbrakeNotifier) extends Logging {

  private val facebookNameSpace = "fortytwoinc"
  private val facebookJoinAction = "join"
  private val facebookKeepAction = "keep"

  def publishKeep(userId: Id[User], keep: Keep, library: Library): Unit = {
    require(keep.userId == userId, s"User $userId cannot publish to Facebook a keep by user ${keep.userId}")
    if (library.visibility == LibraryVisibility.PUBLISHED && hasFacebookExperiment(userId)) {
      db.readOnlyMaster { implicit session => socialUserInfoRepo.getByUser(userId).find(u => u.networkType == SocialNetworks.FACEBOOK) } match {
        case None => log.info(s"user $userId is not connected to facebook!")
        case Some(sui) =>
          val libOwner = db.readOnlyMaster { implicit session => userRepo.get(library.ownerId) }
          val libraryUrl = s"""https://www.kifi.com${Library.formatLibraryPath(libOwner.username, library.slug)}"""
          postOpenGraphAction(sui, facebookKeepAction, "library" -> libraryUrl, "object" -> keep.url)
      }
    }
  }

  def publishLibraryMembership(userId: Id[User], library: Library): Unit = {
    if (library.visibility == LibraryVisibility.PUBLISHED && hasFacebookExperiment(userId)) {
      db.readOnlyMaster { implicit session => socialUserInfoRepo.getByUser(userId).find(u => u.networkType == SocialNetworks.FACEBOOK) } match {
        case None => log.info(s"user $userId is not connected to facebook!")
        case Some(sui) =>
          val libOwner = db.readOnlyMaster { implicit session => userRepo.get(library.ownerId) }
          val libraryUrl = s"""https://www.kifi.com${Library.formatLibraryPath(libOwner.username, library.slug)}"""
          postOpenGraphAction(sui, facebookJoinAction, "library" -> libraryUrl)
      }
    }
  }

  private def hasFacebookExperiment(userId: Id[User]) = {
    db.readOnlyMaster { implicit session => experimentCommander.userHasExperiment(userId, ExperimentType.FACEBOOK_POST) }
  }

  protected def getFbAccessToken(socialUserInfo: SocialUserInfo): String = {
    val credentials = socialUserInfo.credentials.getOrElse(throw new Exception("Can't find credentials for %s".format(socialUserInfo)))
    val oAuth2Info = credentials.oAuth2Info.getOrElse(throw new Exception("Can't find oAuth2Info for %s".format(socialUserInfo)))
    oAuth2Info.accessToken
  }

  private def postOpenGraphAction(socialUserInfo: SocialUserInfo, action: String, properties: (String, String)*): Unit = {
    if (properties.nonEmpty) {
      require(socialUserInfo.networkType == SocialNetworks.FACEBOOK, s"Unexpected network type ${socialUserInfo.networkType} for social user info $socialUserInfo")
      val accessToken = getFbAccessToken(socialUserInfo)
      val client = httpClient.withTimeout(CallTimeouts(responseTimeout = Some(2 * 60 * 1000), maxJsonParseTime = Some(20000)))
      val tracer = new StackTrace()
      val actionParameters = properties.map {
        case (key, value) =>
          URLEncoder.encode(key, UTF8) + "=" + URLEncoder.encode(value, UTF8)
      }.mkString("&")
      val url = s"https://graph.facebook.com/v2.2/${socialUserInfo.socialId.id}/$facebookNameSpace:$action?access_token=$accessToken&$actionParameters"
      log.info(s"posting to FB an action of user ${socialUserInfo.userId.get} with url $url")
      client.postTextFuture(DirectUrl(url), "").onComplete {
        case Success(res) =>
          log.info(s"sent FB action with res = ${res.status}")
        case Failure(e) =>
          airbrake.notify(s"FB didn't like our posting of action: $url", tracer.withCause(e))
      }
    }
  }

}
