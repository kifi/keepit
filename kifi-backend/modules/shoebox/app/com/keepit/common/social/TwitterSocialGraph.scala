package com.keepit.common.social

import com.google.inject.Inject
import com.keepit.commanders.{ KifiInstallationCommander, LibraryImageCommander, ProcessedImageSize }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.store.S3ImageStore
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
import com.keepit.eliza.{ UserPushNotificationCategory, LibraryPushNotificationCategory, PushNotificationExperiment, ElizaServiceClient }
import com.keepit.model.SocialUserInfoStates._
import com.keepit.model._
import com.keepit.social._
import com.ning.http.client.providers.netty.NettyResponse
import play.api.http.Status._
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.libs.oauth.OAuthCalculator
import play.api.libs.ws.{ WSResponse, WS }
import securesocial.core.{ IdentityId, OAuth2Settings }
import twitter4j.{ StatusUpdate, TwitterFactory, Twitter }
import twitter4j.media.{ ImageUpload, MediaProvider, ImageUploadFactory }
import twitter4j.conf.ConfigurationBuilder

import scala.concurrent.{ ExecutionContext, Await, Future }
import scala.concurrent.duration._
import scala.util.{ Success, Failure, Try }
import scala.collection.JavaConversions._

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class PagedIds(
  prev: TwitterId,
  ids: Seq[TwitterId],
  next: TwitterId)

object PagedIds {
  implicit val format = (
    (__ \ 'previous_cursor).format[TwitterId] and
    (__ \ 'ids).format[Seq[TwitterId]] and
    (__ \ 'next_cursor).format[TwitterId]
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
    s3ImageStore: S3ImageStore,
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
    elizaServiceClient: ElizaServiceClient,
    kifiInstallationCommander: KifiInstallationCommander,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext,
    userRepo: UserRepo) extends TwitterSocialGraph with Logging {

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
        profileUrl = info.profileUrl.map(_.toString) orElse sui.profileUrl,
        username = info.profileUrl.map(_.toString).map(url => url.substring(url.lastIndexOf("/") + 1)) orElse sui.username
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
      socialUserInfoRepo.save(socialUserInfo.withState(SocialUserInfoStates.APP_NOT_AUTHORIZED).withLastGraphRefresh())
    } map { saved =>
      log.info(s"[revokePermissions] updated: $saved")
    }
  }

  private def getOAuth1Info(socialUserInfo: SocialUserInfo): OAuth1TokenInfo = {
    val credentials = socialUserInfo.credentials.getOrElse(throw new Exception(s"Can't find credentials for $socialUserInfo"))
    credentials.oAuth1Info.getOrElse(throw new Exception(s"Can't find oAuth1Info for $socialUserInfo"))
  }
  private def getTwtrUserId(socialUserInfo: SocialUserInfo): TwitterId = TwitterId(socialUserInfo.socialId.id.toLong)

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

      SafeFuture {
        log.info(s"[fetchSocialUserInfo(${socialUserInfo.socialId})] fetching twitter_syncs for ${friendIds.length} friends...")
        friendIds.grouped(100) foreach { userIds =>
          val socialUserInfos = db.readOnlyReplica { implicit s =>
            socialUserInfoRepo.getBySocialIds(userIds.map(id => SocialId(id.toString)))
          }
          val twitterHandles = socialUserInfos.flatMap(_.username).toSet
          db.readOnlyMaster { implicit s =>
            twitterSyncStateRepo.getTwitterSyncsByFriendIds(twitterHandles)
          } map { twitterSyncState =>
            db.readWrite { implicit s =>
              val libraryId = twitterSyncState.libraryId
              val user = userRepo.get(userId)
              val library = libraryRepo.get(libraryId)
              val libOwner = basicUserRepo.load(library.ownerId)
              val ownerImage = s3ImageStore.avatarUrlByUser(libOwner)
              val libLink = s"""https://www.kifi.com${Library.formatLibraryPath(libOwner.username, library.slug)}"""
              val libImageOpt = libraryImageCommander.getBestImageForLibrary(library.id.get, ProcessedImageSize.Medium.idealSize)
              if (library.state == LibraryStates.ACTIVE && libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId, None).isEmpty) {
                log.info(s"[fetchSocialUserInfo(${socialUserInfo.socialId})] auto-joining user ${userId} twitter_sync library ${libraryId}")
                libraryMembershipRepo.save(LibraryMembership(libraryId = libraryId, userId = userId, access = LibraryAccess.READ_ONLY))
                notifyUserOnLibraryFollow(user, twitterSyncState, library, libOwner, ownerImage, libLink, libImageOpt)
                notifyOwnerOnLibraryFollow(user, library, libOwner, ownerImage, libLink, libImageOpt)
              }
            }
          }
        }
        log.info(s"[fetchSocialUserInfo(${socialUserInfo.socialId})] finished auto-joining all twitter_sync libraries")
      }

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

  private def notifyUserOnLibraryFollow(user: User, twitterSyncState: TwitterSyncState, library: Library, libOwner: BasicUser, ownerImage: String, libLink: String, libImageOpt: Option[LibraryImage]): Future[Any] = {
    val userId = user.id.get
    elizaServiceClient.sendGlobalNotification( //push sent
      userIds = Set(userId),
      title = s"${libOwner.firstName} created a Library",
      body = s"You're being notified because you're following @${twitterSyncState.twitterHandle} on Twitter",
      linkText = "Let's take a look!",
      linkUrl = libLink,
      imageUrl = ownerImage,
      sticky = false,
      category = NotificationCategory.User.LIBRARY_FOLLOWING,
      extra = Some(Json.obj(
        "inviter" -> libOwner,
        "library" -> Json.toJson(LibraryNotificationInfo.fromLibraryAndOwner(library, libImageOpt, libOwner))
      ))
    ) map { _ =>
        if (user.createdAt > clock.now.minusMinutes(30)) {
          //send a push notifications only if the user was created more then 30 minutes ago.
          val message = s"${libOwner.firstName} created a Twitter Library"
          val canSendPush = kifiInstallationCommander.isMobileVersionGreaterThen(userId, KifiAndroidVersion("2.2.4"), KifiIPhoneVersion("2.1.0"))
          if (canSendPush) {
            elizaServiceClient.sendLibraryPushNotification(
              userId,
              message,
              library.id.get,
              libLink,
              PushNotificationExperiment.Experiment1,
              LibraryPushNotificationCategory.LibraryInvitation)
          }
        }
      }
  }

  private def notifyOwnerOnLibraryFollow(follower: User, lib: Library, owner: BasicUser, ownerImage: String, libLink: String, libImageOpt: Option[LibraryImage]): Future[Any] = {
    val message = s"${follower.firstName} ${follower.lastName} is following you on Twitter"
    elizaServiceClient.sendGlobalNotification( //push sent
      userIds = Set(lib.ownerId),
      title = "New Library Follower",
      body = message,
      linkText = s"See ${follower.firstName}’s profile",
      linkUrl = s"https://www.kifi.com/${follower.username.value}",
      imageUrl = s3ImageStore.avatarUrlByUser(follower),
      sticky = false,
      category = NotificationCategory.User.LIBRARY_FOLLOWED,
      unread = false,
      extra = Some(Json.obj(
        "follower" -> BasicUser.fromUser(follower),
        "library" -> Json.toJson(LibraryNotificationInfo.fromLibraryAndOwner(lib, libImageOpt, owner))
      ))
    ) map { _ =>
        val canSendPush = kifiInstallationCommander.isMobileVersionGreaterThen(lib.ownerId, KifiAndroidVersion("2.2.4"), KifiIPhoneVersion("2.1.0"))
        if (canSendPush) {
          elizaServiceClient.sendUserPushNotification(
            userId = lib.ownerId,
            message = message,
            recipient = follower,
            pushNotificationExperiment = PushNotificationExperiment.Experiment1,
            category = UserPushNotificationCategory.NewLibraryFollower)
        }
      }
  }

  protected def handleError(tag: String, endpoint: String, sui: SocialUserInfo, uvName: UserValueName, cursor: TwitterId, resp: WSResponse): Unit = {
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
        db.readWrite { implicit s => socialUserInfoRepo.save(sui.copy(state = APP_NOT_AUTHORIZED, lastGraphRefresh = Some(clock.now))) }
      case _ =>
    }
  }

  protected def lookupUsers(sui: SocialUserInfo, accessToken: OAuth1TokenInfo, mutualFollows: Set[TwitterId]): Future[JsValue] = {
    log.info(s"[lookupUsers] mutualFollows(len=${mutualFollows.size}): ${mutualFollows.take(20).mkString(",")}... sui=$sui")
    val endpoint = "https://api.twitter.com/1.1/users/lookup.json"
    val sorted = mutualFollows.toSeq.sortBy(_.id) // expensive
    val accF = FutureHelpers.foldLeftUntil[Seq[TwitterId], JsArray](sorted.grouped(100).toIterable)(JsArray()) { (a, c) =>
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
      chunkF map { chunk =>
        val updatedAcc = a ++ chunk.as[JsArray]
        val done = a.value.length == updatedAcc.value.length
        (updatedAcc, done)
      }
    }
    accF map { acc =>
      log.info(s"[lookupUsers.prevAcc] prevAcc(len=${acc.value.length}):${acc.value}")
      acc
    }
  }

  def fetchIds(sui: SocialUserInfo, accessToken: OAuth1TokenInfo, userId: TwitterId, endpoint: String): Future[Seq[TwitterId]] = {
    def pagedFetchIds(page: Int, cursor: TwitterId, count: Long): Future[Seq[TwitterId]] = {
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
            if (next.id > 0) {
              pagedFetchIds(page + 1, next, count) map { seq =>
                pagedIds.ids ++ seq
              }
            } else {
              Future.successful(pagedIds.ids)
            }
          case _ =>
            val name = if (endpoint.contains("friends")) UserValueName.TWITTER_FRIENDS_CURSOR else UserValueName.TWITTER_FOLLOWERS_CURSOR
            handleError(s"pagedFetchIds#$page", endpoint, sui, name, cursor, resp)
            Future.successful(Seq.empty[TwitterId])
        }
      }
    }
    pagedFetchIds(0, TwitterId(-1), 5000)
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
