package com.keepit.common.social

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commanders.{ LibraryImageCommander, PathCommander, ProcessedImageSize }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{ AirbrakeNotifier, StackTrace }
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.oauth._
import com.keepit.common.core._
import com.keepit.common.oauth.{ OAuth1Configuration, ProviderIds, TwitterOAuthProvider, TwitterUserInfo }
import com.keepit.common.social.TwitterSyncError.{ HandleDoesntExist, RateLimit, TokenExpired, UnknownError }
import com.keepit.common.store.S3ImageStore
import com.keepit.common.time.{ Clock, _ }
import com.keepit.model.SocialUserInfoStates._
import com.keepit.model._
import com.keepit.social._
import com.keepit.social.twitter.{ TwitterHandle, TwitterUserId }
import com.ning.http.client.providers.netty.NettyResponse
import play.api.Play.current
import play.api.http.Status._
import play.api.libs.functional.syntax._
import play.api.libs.json.{ JsValue, _ }
import play.api.libs.oauth.OAuthCalculator
import play.api.libs.ws.{ WS, WSResponse }
import securesocial.core.{ IdentityId, OAuth2Settings }
import twitter4j.conf.ConfigurationBuilder
import twitter4j.{ StatusUpdate, Twitter, TwitterFactory }

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

case class PagedIds(
  prev: TwitterUserId,
  ids: Seq[TwitterUserId],
  next: TwitterUserId)

object PagedIds {
  implicit val format = (
    (__ \ 'previous_cursor).format[TwitterUserId] and
    (__ \ 'ids).format[Seq[TwitterUserId]] and
    (__ \ 'next_cursor).format[TwitterUserId]
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

@ImplementedBy(classOf[TwitterSocialGraphImpl])
trait TwitterSocialGraph extends SocialGraph {
  val networkType: SocialNetworkType = SocialNetworks.TWITTER

  def sendDM(socialUserInfo: SocialUserInfo, receiverUserId: Long, msg: String): Future[WSResponse]
  def sendTweet(socialUserInfo: SocialUserInfo, image: Option[File], msg: String): Unit
  def fetchHandleTweets(socialUserInfoOpt: Option[SocialUserInfo], handle: TwitterHandle, lowerBoundId: Option[Long], upperBoundId: Option[Long]): Future[Either[TwitterSyncError, Seq[JsObject]]] //uses app auth if no social user info is given
  def fetchHandleFavourites(socialUserInfoOpt: Option[SocialUserInfo], handle: TwitterHandle, lowerBoundId: Option[Long], upperBoundId: Option[Long]): Future[Either[TwitterSyncError, Seq[JsObject]]]
}

class TwitterSocialGraphImpl @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    clock: Clock,
    oauth1Config: OAuth1Configuration,
    twtrOAuthProvider: TwitterOAuthProvider,
    userValueRepo: UserValueRepo,
    twitterSyncStateRepo: TwitterSyncStateRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryRepo: LibraryRepo,
    basicUserRepo: BasicUserRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    libraryImageCommander: LibraryImageCommander,
    libPathCommander: PathCommander,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext,
    userRepo: UserRepo) extends TwitterSocialGraph with Logging {

  val providerConfig = oauth1Config.getProviderConfig(ProviderIds.Twitter.id).get

  private def twitterClient(socialUserInfo: SocialUserInfo): Twitter = {
    new TwitterFactory(twitterConfig(socialUserInfo)).getInstance()
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
    val suiF = twtrOAuthProvider.getRichIdentity(getOAuth1Info(sui)) map {
      case twitterIdentity =>
        log.info(s"[updateSocialUserInfo] Identity: $twitterIdentity")
        sui.copy(
          pictureUrl = twitterIdentity.pictureUrl orElse sui.pictureUrl,
          profileUrl = twitterIdentity.profileUrl orElse sui.profileUrl,
          username = twitterIdentity.profileUrl.map(url => url.substring(url.lastIndexOf("/") + 1)) orElse sui.username
        )
    }
    Await.result(suiF, 5 minutes)
  }

  // make this async
  // Protip: Just don't commit it then before dependencies are built around it.
  def vetJsAccessToken(settings: OAuth2Settings, json: JsValue): Try[IdentityId] = {
    val token = json.as[OAuth1TokenInfo]
    val idF = twtrOAuthProvider.getIdentityId(token).imap(Success(_)).recover { case e: Exception => Failure(e) }
    Await.result(idF, 5 minutes)
  }

  def revokePermissions(socialUserInfo: SocialUserInfo): Future[Unit] = {
    // Twitter does not support this via API; user can revoke permissions via twitter.com
    db.readWriteAsync { implicit s =>
      socialUserInfoRepo.save(socialUserInfo.withState(SocialUserInfoStates.APP_NOT_AUTHORIZED).withLastGraphRefresh())
    } map { saved =>
      log.info(s"[revokePermissions] updated: $saved")
    }
  }

  private def getOAuth1Info(socialUserInfo: SocialUserInfo): OAuth1TokenInfo = {
    val credentials = socialUserInfo.credentials.getOrElse(throw new Exception(s"Can't find credentials for $socialUserInfo"))
    credentials.oAuth1Info.getOrElse(throw new Exception(s"Can't find oAuth1Info for $socialUserInfo"))
  }
  private def getTwtrUserId(socialUserInfo: SocialUserInfo): TwitterUserId = TwitterUserId(socialUserInfo.socialId.id.toLong)

  // make this async
  def fetchSocialUserRawInfo(socialUserInfo: SocialUserInfo): Option[SocialUserRawInfo] = {
    val userId = socialUserInfo.userId.get
    val accessToken = getOAuth1Info(socialUserInfo)
    val twitterUserId = getTwtrUserId(socialUserInfo)

    val followerIdsEndpoint = "https://api.twitter.com/1.1/followers/ids.json"
    val followerIdsF = fetchIds(socialUserInfo, accessToken, twitterUserId, followerIdsEndpoint)

    val friendIdsEndpoint = "https://api.twitter.com/1.1/friends/ids.json"
    val friendIdsF = fetchIds(socialUserInfo, accessToken, twitterUserId, friendIdsEndpoint)

    val mutualFollowsF = for {
      followerIds <- followerIdsF
      friendIds <- friendIdsF
    } yield {
      val mutualFollows = followerIds.toSet.intersect(friendIds.toSet)
      log.info(s"[fetchSocialUserInfo(${socialUserInfo.socialId})] friendIds(len=${friendIds.length}):${friendIds.take(10)} followerIds(len=${followerIds.length}):${followerIds.take(10)} mutual(len=${mutualFollows.size}):${mutualFollows.take(10)}")

      fetchTwitterSyncs(userId, socialUserInfo, friendIds)
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

  private def fetchTwitterSyncs(userId: Id[User], socialUserInfo: SocialUserInfo, friendIds: Seq[TwitterUserId]) = {
    SafeFuture {
      log.info(s"[fetchSocialUserInfo(${socialUserInfo.socialId})] fetching twitter_syncs for ${friendIds.length} friends...")
      friendIds.grouped(100) foreach { userIds =>
        val socialUserInfos = db.readOnlyReplica { implicit s =>
          socialUserInfoRepo.getByNetworkAndSocialIds(SocialNetworks.TWITTER, userIds.map(id => SocialId(id.toString)).toSet)
        }
        val twitterHandles = socialUserInfos.values.flatMap(_.username.map(TwitterHandle(_))).toSet
        db.readOnlyMaster { implicit s =>
          twitterSyncStateRepo.getTwitterSyncsByFriendIds(twitterHandles)
        } map { twitterSyncState =>
          db.readWrite { implicit s =>
            val libraryId = twitterSyncState.libraryId
            val user = userRepo.get(userId)
            val library = libraryRepo.get(libraryId)
            val libOwner = basicUserRepo.load(library.ownerId)
            if (library.visibility == LibraryVisibility.PUBLISHED && library.state == LibraryStates.ACTIVE && user.state == UserStates.ACTIVE && libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId, None).isEmpty) {
              log.info(s"[fetchSocialUserInfo(${socialUserInfo.socialId})] auto-joining user ${userId} twitter_sync library ${libraryId}")
              libraryMembershipRepo.save(LibraryMembership(libraryId = libraryId, userId = userId, access = LibraryAccess.READ_ONLY))
            }
          }
        }
      }
      log.info(s"[fetchSocialUserInfo(${socialUserInfo.socialId})] finished auto-joining all twitter_sync libraries")
    }
  }

  private def handleError(tag: String, endpoint: String, sui: SocialUserInfo, uvName: UserValueName, cursor: TwitterUserId, resp: WSResponse, params: Any): Unit = {
    val nettyResp = resp.underlying[NettyResponse]
    def warn(notify: Boolean): Unit = {
      val errorMessage = resp.status match {
        case TOO_MANY_REQUEST => "hit rate-limit"
        case UNAUTHORIZED => "unauthorized or invalid/expired token"
        case _ => "non-OK response"
      }
      val errMsg = s"[$tag] Error for user ${sui.userId} ${sui.fullName} sui ${sui.id}: $errorMessage for $endpoint. status=${resp.status} body=${resp.body}; request.uri=${nettyResp.getUri}; request params=$params"
      if (notify)
        airbrake.notify(errMsg)
      else
        log.error(errMsg)
    }
    warn(false) // set to false to reduce noise
    resp.status match {
      case TOO_MANY_REQUEST => // 429: rate-limit exceeded
        db.readWrite { implicit s =>
          userValueRepo.setValue(sui.userId.get, uvName, cursor)
        }
      case UNAUTHORIZED => // 401: invalid or expired token
        db.readWrite { implicit s => socialUserInfoRepo.save(sui.copy(state = APP_NOT_AUTHORIZED, lastGraphRefresh = Some(clock.now))) }
      case _ =>
    }
  }

  protected def lookupUsers(sui: SocialUserInfo, accessToken: OAuth1TokenInfo, mutualFollows: Set[TwitterUserId]): Future[JsValue] = {
    log.info(s"[lookupUsers] mutualFollows(len=${mutualFollows.size}): ${mutualFollows.take(20).mkString(",")}... sui=$sui")
    val endpoint = "https://api.twitter.com/1.1/users/lookup.json"
    val sorted = mutualFollows.toSeq.sortBy(_.id) // expensive
    val accF = FutureHelpers.foldLeftUntil[Seq[TwitterUserId], JsArray](sorted.grouped(100).toIterable)(JsArray()) { (a, c) =>
      val params = Map("user_id" -> c.map(_.id).mkString(","), "include_entities" -> false.toString)
      val serializedParams = params.map(kv => (kv._1, Seq(kv._2)))
      val chunkF = WS.url(endpoint)
        .sign(OAuthCalculator(providerConfig.key, accessToken))
        .post(serializedParams)
        .map { resp =>
          log.info(s"[lookupUsers] prevAcc.len=${a.value.length} cursor=${c.head} response.json len=${resp.json.toString().length}")
          resp.status match {
            case OK => resp.json
            case _ =>
              handleError("lookupUsers", endpoint, sui, UserValueName.TWITTER_LOOKUP_CURSOR, c.head, resp, serializedParams)
              JsArray(Seq.empty[JsValue])
          }
        }
      chunkF map { chunk =>
        val updatedAcc = a ++ chunk.as[JsArray]
        val done = a.value.length == updatedAcc.value.length
        (updatedAcc, done)
      }
    }
    accF map { acc =>
      log.info(s"[lookupUsers.prevAcc] prevAcc(len=${acc.value.length})")
      acc
    }
  }

  protected def fetchIds(sui: SocialUserInfo, accessToken: OAuth1TokenInfo, userId: TwitterUserId, endpoint: String): Future[Seq[TwitterUserId]] = {
    def pagedFetchIds(page: Int, cursor: TwitterUserId, count: Long): Future[Seq[TwitterUserId]] = {
      log.info(s"[pagedFetchIds] userId=$userId endpoint=$endpoint count=$count cursor=$cursor")
      val queryStrings = Seq("user_id" -> userId.id.toString, "cursor" -> cursor.id.toString, "count" -> count.toString)
      val call = WS.url(endpoint)
        .sign(OAuthCalculator(providerConfig.key, accessToken))
        .withQueryString(queryStrings: _*)
        .get()
      call flatMap { resp =>
        resp.status match {
          case OK =>
            val pagedIds = resp.json.as[PagedIds]
            log.info(s"[pagedFetchIds#$page] cursor=$cursor userId=$userId endpoint=$endpoint pagedIds len=${pagedIds.ids.length}")
            val next = pagedIds.next
            if (next.id > 0) {
              pagedFetchIds(page + 1, next, count) map { seq =>
                pagedIds.ids ++ seq
              }
            } else {
              Future.successful(pagedIds.ids)
            }
          case _ =>
            val name = if (endpoint.contains("friends")) UserValueName.TWITTER_FRIENDS_CURSOR else UserValueName.TWITTER_FOLLOWERS_CURSOR
            handleError(s"pagedFetchIds#$page", endpoint, sui, name, cursor, resp, queryStrings)
            Future.successful(Seq.empty[TwitterUserId])
        }
      }
    }
    pagedFetchIds(0, TwitterUserId(-1), 5000)
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

  def fetchHandleTweets(socialUserInfoOpt: Option[SocialUserInfo], handle: TwitterHandle, lowerBoundId: Option[Long], upperBoundId: Option[Long]): Future[Either[TwitterSyncError, Seq[JsObject]]] = {
    val endpoint = "https://api.twitter.com/1.1/statuses/user_timeline.json"
    val query = Seq("screen_name" -> Some(handle.value), "count" -> Some("200"), "since_id" -> lowerBoundId.map(_.toString), "max_id" -> upperBoundId.map(id => (id - 1).toString)).collect { case (k, Some(v)) => k -> v }

    fetch(endpoint, query, socialUserInfoOpt, handle)
  }

  def fetchHandleFavourites(socialUserInfoOpt: Option[SocialUserInfo], handle: TwitterHandle, lowerBoundId: Option[Long], upperBoundId: Option[Long]): Future[Either[TwitterSyncError, Seq[JsObject]]] = {
    val endpoint = "https://api.twitter.com/1.1/favorites/list.json"
    val query = Seq("screen_name" -> Some(handle.value), "count" -> Some("200"), "since_id" -> lowerBoundId.map(_.toString), "max_id" -> upperBoundId.map(id => (id - 1).toString)).collect { case (k, Some(v)) => k -> v }

    fetch(endpoint, query, socialUserInfoOpt, handle)
  }

  private def fetch(endpoint: String, query: Seq[(String, String)], socialUserInfoOpt: Option[SocialUserInfo], handle: TwitterHandle): Future[Either[TwitterSyncError, Seq[JsObject]]] = {
    val stackTrace = new StackTrace()
    val sig: OAuthCalculator = socialUserInfoOpt.flatMap { socialUserInfo =>
      if (socialUserInfo.state != SocialUserInfoStates.TOKEN_EXPIRED) {
        Some(OAuthCalculator(providerConfig.key, getOAuth1Info(socialUserInfo)))
      } else {
        None
      }
    } getOrElse {
      OAuthCalculator(providerConfig.key, OAuth1TokenInfo(providerConfig.accessToken.key, providerConfig.accessToken.secret))
    }

    log.info(s"[twfetch] Fetching tweets for $handle using ${socialUserInfoOpt.flatMap(_.userId).map(_.toString).getOrElse("system")} token. $query")
    val call = WS.url(endpoint).sign(sig).withQueryString(query: _*)
    call.get().map { response =>
      if (response.status == 200) {
        Right(response.json.as[JsArray].value.map(_.as[JsObject]))
      } else if (response.status == 429 || response.status == 420) { //rate limit
        Left(RateLimit)
      } else if (response.status == 401) { //token not good
        Left(TokenExpired(socialUserInfoOpt))
      } else if (response.status == 404) {
        val errorCodes = (response.json \\ "code").map(_.as[Int])
        if (errorCodes.contains(34)) { // "Sorry, that page does not exist"
          Left(HandleDoesntExist(handle))
        } else {
          Left(UnknownError(query.toString, response.json.toString))
        }
      } else {
        Left(UnknownError(query.toString, response.status.toString + ": " + response.json.toString))
      }
    }.recover {
      case t: Throwable =>
        Left(UnknownError(query.toString, t.getMessage))
    }
  }
}

sealed trait TwitterSyncError
object TwitterSyncError {
  case object RateLimit extends TwitterSyncError
  case class TokenExpired(suiOpt: Option[SocialUserInfo]) extends TwitterSyncError
  case class HandleDoesntExist(handle: TwitterHandle) extends TwitterSyncError
  case class UnknownError(request: String, response: String) extends TwitterSyncError
}
