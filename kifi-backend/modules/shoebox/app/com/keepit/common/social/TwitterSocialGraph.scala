package com.keepit.common.social

import com.google.inject.Inject
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.net.HttpClient
import com.keepit.common.oauth._
import com.keepit.common.time.Clock
import com.keepit.common.core._
import com.keepit.model._
import com.keepit.social._
import com.ning.http.client.providers.netty.NettyResponse
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.libs.oauth.OAuthCalculator
import play.api.libs.ws.WS
import securesocial.core.{ IdentityId, OAuth2Settings }

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.util.{ Success, Failure, Try }

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class PagedIds(
  prev: Long,
  ids: Seq[Long],
  next: Long)

object PagedIds {
  implicit val format = (
    (__ \ 'previous_cursor).format[Long] and
    (__ \ 'ids).format[Seq[Long]] and
    (__ \ 'next_cursor).format[Long]
  )(PagedIds.apply _, unlift(PagedIds.unapply))
}

case class PagedTwitterUserInfos(
  prev: Long,
  users: Seq[TwitterUserInfo],
  next: Long)

object PagedTwitterUserInfos {
  implicit val format = (
    (__ \ 'previous_cursor).format[Long] and
    (__ \ 'users).format[Seq[TwitterUserInfo]] and
    (__ \ 'next_cursor).format[Long]
  )(PagedTwitterUserInfos.apply _, unlift(PagedTwitterUserInfos.unapply))

}

class TwitterSocialGraph @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    suiRepo: SocialUserInfoRepo,
    oauth1Config: OAuth1Configuration,
    twtrOAuthProvider: TwitterOAuthProvider,
    socialRepo: SocialUserInfoRepo) extends SocialGraph with Logging {

  val networkType: SocialNetworkType = SocialNetworks.TWITTER

  val providerConfig = oauth1Config.getProviderConfig(ProviderIds.Twitter.id).get

  def extractEmails(parentJson: JsValue): Seq[EmailAddress] = Seq.empty // no email for Twitter

  def extractFriends(parentJson: JsValue): Seq[SocialUserInfo] = {
    parentJson.as[Seq[TwitterUserInfo]].map(TwitterUserInfo.toSocialUserInfo(_)) tap { infos =>
      log.info(s"[extractFriends] infos(len=${infos.length}):${infos.take(20)}...")
    }
  }

  // make this async
  def updateSocialUserInfo(sui: SocialUserInfo, json: JsValue): SocialUserInfo = {
    val suiF = twtrOAuthProvider.getUserProfileInfo(getOAuth1Info(sui)) map { info =>
      log.info(s"[updateSocialUserInfo] picUrl=${info.pictureUrl} profileUrl=${info.profileUrl}; info=$info")
      sui.copy(
        pictureUrl = info.pictureUrl.map(_.toString) orElse sui.pictureUrl,
        profileUrl = info.profileUrl.map(_.toString) orElse sui.profileUrl
      )
    }
    Await.result(suiF, 5 minutes)
  }

  // make this async
  def vetJsAccessToken(settings: OAuth2Settings, json: JsValue): Try[IdentityId] = {
    val token = json.as[OAuth1TokenInfo]
    val idF = twtrOAuthProvider.getUserProfileInfo(token) map { resp =>
      Success(IdentityId(resp.userId.id, resp.providerId.id))
    } recover {
      case t: Throwable => Failure(t)
    }
    Await.result(idF, 5 minutes)
  }

  def revokePermissions(socialUserInfo: SocialUserInfo): Future[Unit] = {
    // Twitter does not support this via API; user can revoke permissions via twitter.com
    db.readWriteAsync { implicit s =>
      socialRepo.save(socialUserInfo.withState(SocialUserInfoStates.APP_NOT_AUTHORIZED).withLastGraphRefresh())
    } map { saved =>
      log.info(s"[revokePermissions] updated: $saved")
    }
  }

  private def getOAuth1Info(socialUserInfo: SocialUserInfo): OAuth1TokenInfo = {
    val credentials = socialUserInfo.credentials.getOrElse(throw new Exception(s"Can't find credentials for $socialUserInfo"))
    credentials.oAuth1Info.getOrElse(throw new Exception(s"Can't find oAuth1Info for $socialUserInfo"))
  }
  private def getTwtrUserId(socialUserInfo: SocialUserInfo): Long = socialUserInfo.socialId.id.toLong

  // make this async
  def fetchSocialUserRawInfo(socialUserInfo: SocialUserInfo): Option[SocialUserRawInfo] = {
    val accessToken = getOAuth1Info(socialUserInfo)
    val userId = getTwtrUserId(socialUserInfo)

    val followerIdsEndpoint = "https://api.twitter.com/1.1/followers/ids.json"
    val followerIdsF = fetchIds(socialUserInfo, accessToken, userId, followerIdsEndpoint)

    val friendIdsEndpoint = "https://api.twitter.com/1.1/friends/ids.json"
    val friendIdsF = fetchIds(socialUserInfo, accessToken, userId, friendIdsEndpoint)

    val mutualFollowsF = for {
      followerIds <- followerIdsF
      friendIds <- friendIdsF
    } yield {
      val mutualFollows = followerIds.toSet.intersect(friendIds.toSet)
      log.info(s"[fetchSocialUserInfo(${socialUserInfo.socialId})] friendIds(len=${friendIds.length}):${friendIds.take(10)} followerIds(len=${followerIds.length}):${followerIds.take(10)} mutual(len=${mutualFollows.size}):${mutualFollows.take(10)}")
      mutualFollows
    }

    val rawInfosF = mutualFollowsF flatMap { mutualFollows =>
      lookupUsers(socialUserInfo, accessToken, mutualFollows)
    }

    val rawInfos = Await.result(rawInfosF, 5 minutes)
    val credentials = socialUserInfo.credentials.get
    Some(
      SocialUserRawInfo(
        socialUserInfo.userId,
        socialUserInfo.id,
        SocialId(credentials.identityId.userId),
        SocialNetworks.TWITTER,
        credentials.fullName,
        Stream(rawInfos)
      )
    )
  }

  protected def lookupUsers(socialUserInfo: SocialUserInfo, accessToken: OAuth1TokenInfo, mutualFollows: Set[Long]): Future[JsValue] = {
    val endpoint = "https://api.twitter.com/1.1/users/lookup.json"
    val accF = FutureHelpers.foldLeft[Set[Long], JsArray](mutualFollows.grouped(100).toIterable)(JsArray()) { (a, c) =>
      val params = Map("user_id" -> c.mkString(","), "include_entities" -> false.toString)
      val chunkF = WS.url(endpoint)
        .sign(OAuthCalculator(providerConfig.key, accessToken))
        .post(params.map(kv => (kv._1, Seq(kv._2))))
        .map { resp =>
          if (resp.status != 200) {
            airbrake.notify(s"[fetchSocialUserInfo] non-OK response from $endpoint. socialUser=$socialUserInfo; status=${resp.status} body=${resp.body}; request=${resp.underlying[NettyResponse]} request.uri=${resp.underlying[NettyResponse].getUri}")
            JsArray(Seq.empty[JsValue])
          } else {
            log.info(s"[lookup] response.json=${resp.json}")
            resp.json
          }
        }
      chunkF map { chunk => a ++ chunk.as[JsArray] }
    }
    accF map { acc =>
      log.info(s"[lookup.acc] acc(len=${acc.value.length}):${acc.value}")
      acc
    }
  }

  protected def fetchIds(socialUserInfo: SocialUserInfo, accessToken: OAuth1TokenInfo, userId: Long, endpoint: String): Future[Seq[Long]] = {
    def pagedFetchIds(page: Int, cursor: Long, count: Long): Future[Seq[Long]] = {
      log.info(s"[pagedFetchIds] userId=$userId endpoint=$endpoint count=$count cursor=$cursor")
      val call = WS.url(endpoint)
        .sign(OAuthCalculator(providerConfig.key, accessToken))
        .withQueryString(
          "user_id" -> userId.toString,
          "cursor" -> cursor.toString,
          "count" -> count.toString)
        .get()
      call flatMap { resp =>
        if (resp.status != 200) {
          airbrake.notify(s"[fetchSocialUserInfo] non-OK response from $endpoint. socialUser=$socialUserInfo; status=${resp.status} body=${resp.body}; request=${resp.underlying[NettyResponse]} request.uri=${resp.underlying[NettyResponse].getUri}")
          Future.successful(Seq.empty[Long])
        } else {
          val pagedIds = resp.json.as[PagedIds]
          log.info(s"[pagedFetchIds#$page] userId=$userId endpoint=$endpoint pagedIds=$pagedIds")
          val next = pagedIds.next
          if (next > 0) {
            pagedFetchIds(page + 1, next, count) map { seq =>
              pagedIds.ids ++ seq
            }
          } else {
            Future.successful(pagedIds.ids)
          }
        }
      }
    }
    pagedFetchIds(0, -1, 5000)
  }

  def extractUserValues(json: JsValue): Map[UserValueName, String] = Map.empty

}
