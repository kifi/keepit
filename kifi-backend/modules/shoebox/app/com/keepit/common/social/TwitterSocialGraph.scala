package com.keepit.common.social

import com.google.inject.Inject
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.store.LibraryImageStore
import com.keepit.common.time._
import com.keepit.common.core._
import com.keepit.common.strings._
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.oauth.{ TwitterUserInfo, TwitterOAuthProvider, OAuth1Configuration, ProviderIds }
import com.keepit.common.core._
import com.keepit.common.time.Clock
import com.keepit.model.SocialUserInfoStates._
import com.keepit.model._
import com.keepit.social._
import com.ning.http.client.providers.netty.NettyResponse
import play.api.http.Status._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.libs.oauth.OAuthCalculator
import play.api.libs.ws.{ WSResponse, WS }
import securesocial.core.{ IdentityId, OAuth2Settings }
import twitter4j.auth.OAuthAuthorization
import twitter4j.{ StatusUpdate, TwitterFactory, Twitter }
import twitter4j.media.{ ImageUpload, MediaProvider, ImageUploadFactory }
import twitter4j.conf.ConfigurationBuilder

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.util.{ Success, Failure, Try }
import scala.collection.JavaConversions._

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

case class TwitterError(message: String, code: Long)

trait TwitterSocialGraph extends SocialGraph {
  val networkType: SocialNetworkType = SocialNetworks.TWITTER

  def sendDM(socialUserInfo: SocialUserInfo, receiverUserId: Long, msg: String): Future[WSResponse]
  def sendTweet(socialUserInfo: SocialUserInfo, image: Option[File], msg: String): Unit
  def fetchTweets(socialUserInfoOpt: Option[SocialUserInfo], handle: String, sinceId: Long): Future[Seq[JsObject]] //uses app auth if no social user info is given
}

class TwitterSocialGraphImpl @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    clock: Clock,
    oauth1Config: OAuth1Configuration,
    twtrOAuthProvider: TwitterOAuthProvider,
    userValueRepo: UserValueRepo,
    socialRepo: SocialUserInfoRepo) extends TwitterSocialGraph with Logging {

  val providerConfig = oauth1Config.getProviderConfig(ProviderIds.Twitter.id).get

  private def twitterClient(socialUserInfo: SocialUserInfo): Twitter = {
    new TwitterFactory(twitterConfig(socialUserInfo)).getInstance()
  }

  private def twitterImageUploadClient(socialUserInfo: SocialUserInfo): ImageUpload = {
    val conf = twitterConfig(socialUserInfo)
    new ImageUploadFactory(conf).getInstance(MediaProvider.TWITTER)
  }

  private def twitterConfig(socialUserInfo: SocialUserInfo) = {
    val accessToken = getOAuth1Info(socialUserInfo)
    val consumerKey = providerConfig.key
    val cb = new ConfigurationBuilder()
    cb.setDebugEnabled(true)
      .setOAuthConsumerKey(consumerKey.key)
      .setOAuthConsumerSecret(consumerKey.secret)
      .setOAuthAccessToken(accessToken.token)
      .setOAuthAccessTokenSecret(accessToken.secret).build()
  }

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

  protected def handleError(tag: String, endpoint: String, sui: SocialUserInfo, uvName: UserValueName, cursor: Long, resp: WSResponse): Unit = {
    val nettyResp = resp.underlying[NettyResponse]
    def warn(notify: Boolean): Unit = {
      val errorMessage = resp.status match {
        case TOO_MANY_REQUEST => "hit rate-limit"
        case UNAUTHORIZED => "unauthorized or invalid/expired token"
        case _ => "non-OK response"
      }
      val errMsg = s"[$tag] Error for user ${sui.userId} ${sui.fullName} sui ${sui.id}: $errorMessage for $endpoint. status=${resp.status} body=${resp.body}; request.uri=${nettyResp.getUri}"
      if (notify)
        airbrake.notify(errMsg)
      else
        log.error(errMsg)
    }
    warn(true) // set to false to reduce noise: see LinkedInSocialGraph
    resp.status match {
      case TOO_MANY_REQUEST => // 429: rate-limit exceeded
        db.readWrite { implicit s =>
          userValueRepo.setValue(sui.userId.get, uvName, cursor)
        }
      case UNAUTHORIZED => // 401: invalid or expired token
        db.readWrite { implicit s => socialRepo.save(sui.copy(state = APP_NOT_AUTHORIZED, lastGraphRefresh = Some(clock.now))) }
      case _ =>
    }
  }

  protected def lookupUsers(sui: SocialUserInfo, accessToken: OAuth1TokenInfo, mutualFollows: Set[Long]): Future[JsValue] = {
    log.info(s"[lookupUsers] mutualFollows(len=${mutualFollows.size}): ${mutualFollows.take(20).mkString(",")}... sui=$sui")
    val endpoint = "https://api.twitter.com/1.1/users/lookup.json"
    val sorted = mutualFollows.toSeq.sorted // expensive
    def pred(prevAcc: JsArray, currAcc: JsArray, c: Seq[Long]) = {
      prevAcc.value.length != currAcc.value.length tap { res =>
        if (!res) log.warn(s"[lookupUsers.pred] prevAcc(len=${prevAcc.value.length}) currAcc.value.length=${currAcc.value.length} c.head=${c.headOption} res=$res")
      }
    }
    val accF = FutureHelpers.foldLeftWhile[Seq[Long], JsArray](sorted.grouped(100).toIterable)(JsArray())({ (a, c) =>
      val params = Map("user_id" -> c.mkString(","), "include_entities" -> false.toString)
      val chunkF = WS.url(endpoint)
        .sign(OAuthCalculator(providerConfig.key, accessToken))
        .post(params.map(kv => (kv._1, Seq(kv._2))))
        .map { resp =>
          log.info(s"[lookupUsers] prevAcc.len=${a.value.length} cursor=${c.head} response.json=${resp.json.toString.abbreviate(400)}")
          resp.status match {
            case OK => resp.json
            case _ =>
              handleError("lookupUsers", endpoint, sui, UserValueName.TWITTER_LOOKUP_CURSOR, c.head, resp)
              JsArray(Seq.empty[JsValue])
          }
        }
      chunkF map { chunk => a ++ chunk.as[JsArray] }
    }, Some(pred))
    accF map { acc =>
      log.info(s"[lookupUsers.prevAcc] prevAcc(len=${acc.value.length}):${acc.value}")
      acc
    }
  }

  protected def fetchIds(sui: SocialUserInfo, accessToken: OAuth1TokenInfo, userId: Long, endpoint: String): Future[Seq[Long]] = {
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
        resp.status match {
          case OK =>
            val pagedIds = resp.json.as[PagedIds]
            log.info(s"[pagedFetchIds#$page] cursor=$cursor userId=$userId endpoint=$endpoint pagedIds=$pagedIds")
            val next = pagedIds.next
            if (next > 0) {
              pagedFetchIds(page + 1, next, count) map { seq =>
                pagedIds.ids ++ seq
              }
            } else {
              Future.successful(pagedIds.ids)
            }
          case _ =>
            val name = if (endpoint.contains("friends")) UserValueName.TWITTER_FRIENDS_CURSOR else UserValueName.TWITTER_FOLLOWERS_CURSOR
            handleError(s"pagedFetchIds#$page", endpoint, sui, name, cursor, resp)
            Future.successful(Seq.empty[Long])
        }
      }
    }
    pagedFetchIds(0, -1, 5000)
  }

  def extractUserValues(json: JsValue): Map[UserValueName, String] = Map.empty

  def sendDM(socialUserInfo: SocialUserInfo, receiverUserId: Long, msg: String): Future[WSResponse] = {
    val endpoint = "https://api.twitter.com/1.1/direct_messages/new.json"
    val call = WS.url(endpoint)
      .sign(OAuthCalculator(providerConfig.key, getOAuth1Info(socialUserInfo)))
      .withQueryString("user_id" -> receiverUserId.toString, "text" -> msg)
      .post(Map.empty[String, Seq[String]])
    call onComplete { tr => log.info(s"[sendDM] receiverUserId=$receiverUserId msg=$msg res=$tr") }
    call
  }

  def sendTweet(socialUserInfo: SocialUserInfo, image: Option[File], msg: String): Unit = try {
    log.info(s"tweeting for ${socialUserInfo.profileUrl} ${socialUserInfo.userId} ${socialUserInfo.fullName} with image: ${image.isDefined} message: $msg")
    val status = new StatusUpdate(msg)
    image.foreach(file => status.setMedia(file))
    val res = twitterClient(socialUserInfo).updateStatus(status)
    log.info(s"twitted status id ${res.getId} for message $msg")
  } catch {
    case e: Exception =>
      val conf = twitterClient(socialUserInfo).getAPIConfiguration
      val limits = twitterClient(socialUserInfo).getRateLimitStatus
      airbrake.notify(s"error tweeting for ${socialUserInfo.profileUrl} ${socialUserInfo.userId} ${socialUserInfo.fullName} with image: ${image.isDefined} message: $msg " +
        s"PhotoSizeLimit:${conf.getPhotoSizeLimit},ShortURLLength:${conf.getShortURLLength},ShortURLLengthHttps:${conf.getShortURLLengthHttps},CharactersReservedPerMedia:${conf.getCharactersReservedPerMedia},limits:$limits", e)
  }

  def fetchTweets(socialUserInfoOpt: Option[SocialUserInfo], handle: String, sinceId: Long): Future[Seq[JsObject]] = {
    val endpoint = "https://api.twitter.com/1.1/statuses/user_timeline.json"
    val sig = socialUserInfoOpt.map { socialUserInfo =>
      OAuthCalculator(providerConfig.key, getOAuth1Info(socialUserInfo))
    } getOrElse {
      OAuthCalculator(providerConfig.key, OAuth1TokenInfo(providerConfig.accessToken.key, providerConfig.accessToken.secret))
    }
    val call = if (sinceId > 0) {
      WS.url(endpoint).sign(sig).withQueryString("screen_name" -> handle, "since_id" -> sinceId.toString, "count" -> "200")
    } else {
      WS.url(endpoint).sign(sig).withQueryString("screen_name" -> handle, "count" -> "200")
    }
    call.get().map { response =>
      if (response.status == 200) {
        response.json.as[JsArray].value.map(_.as[JsObject])
      } else if (response.status == 420) { //rate limit
        Seq.empty
      } else {
        airbrake.notify(s"Failed to get users $handle timeline, status ${response.status}, msg: ${response.json.toString}")
        Seq.empty
      }
    }

  }

}
