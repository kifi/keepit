package com.keepit.controllers.ext

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise
import scala.util.Random

import org.joda.time.Seconds

import com.google.inject.Inject
import com.keepit.classify.{Domain, DomainRepo, DomainStates}
import com.keepit.common.analytics._
import com.keepit.common.controller.FortyTwoCookies.ImpersonateCookie
import com.keepit.common.controller._
import com.keepit.common.db.ExternalId
import com.keepit.common.db.Id
import com.keepit.common.db.State
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.social._
import com.keepit.common.time._
import com.keepit.controllers.core.{KeeperInfoLoader, NetworkInfoLoader}
import com.keepit.model._
import com.keepit.realtime._

import akka.actor.{Cancellable, ActorSystem}
import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.iteratee.Concurrent
import play.api.libs.iteratee.Enumerator
import play.api.libs.iteratee.Iteratee
import play.api.libs.json._
import play.api.mvc.RequestHeader
import play.api.mvc.WebSocket
import play.api.mvc.WebSocket.FrameFormatter
import play.modules.statsd.api.Statsd
import securesocial.core.{Authenticator, UserService, SecureSocial}
import scala.collection.concurrent.TrieMap
import akka.actor.{Cancellable, ActorSystem}
import scala.concurrent.duration.FiniteDuration
import org.joda.time.Seconds
import scala.concurrent.Promise
import scala.concurrent.stm._
import com.keepit.social.{SocialNetworkType, SocialId, CommentWithBasicUser, BasicUser}
import com.keepit.normalizer.NormalizationService

case class StreamSession(userId: Id[User], socialUser: SocialUserInfo, experiments: Set[State[ExperimentType]], adminUserId: Option[Id[User]])

class ExtStreamController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  socialUserInfoRepo: SocialUserInfoRepo,
  userConnectionRepo: UserConnectionRepo,
  userRepo: UserRepo,
  basicUserRepo: BasicUserRepo,
  experimentRepo: UserExperimentRepo,
  userValueRepo: UserValueRepo,
  userChannel: UserChannel,
  uriChannel: UriChannel,
  userToDomainRepo: UserToDomainRepo,
  userNotificationRepo: UserNotificationRepo,
  eventPersister: EventPersister,
  keeperInfoLoader: KeeperInfoLoader,
  networkInfoLoader: NetworkInfoLoader,
  sliderRuleRepo: SliderRuleRepo,
  urlPatternRepo: URLPatternRepo,
  commentRepo: CommentRepo,
  commentReadRepo: CommentReadRepo,
  normUriRepo: NormalizedURIRepo,
  normalizationService: NormalizationService,
  domainRepo: DomainRepo,
  commentWithBasicUserRepo: CommentWithBasicUserRepo,
  eventHelper: EventHelper,
  system: ActorSystem,
  impersonateCookie: ImpersonateCookie,
  implicit private val clock: Clock,
  implicit private val fortyTwoServices: FortyTwoServices)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  // A hack which allows us to pass the SecureSocial session ID (sid) by query string.
  // This is mainly a workaround for the mobile client, since the library we use doesn't support cookies
  private def getAuthenticatorFromRequest()(implicit request: RequestHeader): Option[Authenticator] =
    SecureSocial.authenticatorFromRequest orElse {
      (for {
        sid <- request.queryString.get("sid").map(_.head)
        auth <- Authenticator.find(sid).fold(_ => None, Some(_)).flatten
      } yield auth) match {
        case Some(auth) if !auth.isValid =>
          Authenticator.delete(auth.id)
          None
        case maybeAuth => maybeAuth
      }
    }

  private def authenticate(implicit request: RequestHeader): Option[StreamSession] = {
    // Backdoor for mobile development
    val backdoorAuth = (for (
      // key <- request.getQueryString("key");
      userIdStr <- request.getQueryString("userId")
    ) yield {
      try {
        val userId = Id[User](userIdStr.toInt)
        val keyValid = true//BCrypt.checkpw(userId + "DQVXJwAZYuQ3rUo75ltbglBK", key)
        if(keyValid) {
          val (socialUser, experiments) = db.readOnly { implicit session =>
            val socialUser = socialUserInfoRepo.getByUser(userId).head
            val experiments = experimentRepo.getUserExperiments(userId)
            (socialUser, experiments)
          }
          Some(StreamSession(userId, socialUser, experiments, None))
        }
        else None
      } catch {
        case ex: Throwable => None // bad inputs
      }
    }).flatten

    if(backdoorAuth.isDefined)
      return backdoorAuth // This is very temporary, sorry for the return.

    /*
     * Unfortunately, everything related to existing secured actions intimately deals with Action, Request, Result, etc.
     * WebSockets cannot use these, so I've implemented what I need below.
     */
    for (
      auth <- getAuthenticatorFromRequest();
      secSocialUser <- UserService.find(auth.identityId)
    ) yield {

      val impersonatedUserIdOpt: Option[ExternalId[User]] =
        impersonateCookie.decodeFromCookie(request.cookies.get(impersonateCookie.COOKIE_NAME))

      db.readOnly { implicit session =>
        val socialUser = socialUserInfoRepo.get(SocialId(secSocialUser.identityId.userId),
          SocialNetworkType(secSocialUser.identityId.providerId))
        val userId = socialUser.userId.get
        val experiments = experimentRepo.getUserExperiments(userId)
        impersonatedUserIdOpt match {
          case Some(impExtUserId) if experiments.contains(ExperimentTypes.ADMIN) =>
            val impUserId = userRepo.get(impExtUserId).id.get
            val impSocUserInfo = socialUserInfoRepo.getByUser(impUserId)
            StreamSession(impUserId, impSocUserInfo.head, experiments, Some(userId))
          case None if experiments.contains(ExperimentTypes.ADMIN) =>
            StreamSession(userId, socialUser, experiments, Some(userId))
          case _ =>
            StreamSession(userId, socialUser, experiments, None)
        }
      }
    }
  }

  implicit val jsonFrame: FrameFormatter[JsArray] =
    FrameFormatter.stringFrame.transform(
      Json.stringify,
      in =>
        Json.parse(in) match {
          case j: JsArray => j
          case j: JsValue => Json.arr(j)
        })

  private def asyncIteratee(f: JsArray => Unit): Iteratee[JsArray, Unit] = {
    import play.api.libs.iteratee._
    def step(i: Input[JsArray]): Iteratee[JsArray, Unit] = i match {
      case Input.EOF => Done(Unit, Input.EOF)
      case Input.Empty => Cont[JsArray, Unit](i => step(i))
      case Input.El(e) =>
        Akka.future { f(e) }
        Cont[JsArray, Unit](i => step(i))
    }
    (Cont[JsArray, Unit](i => step(i)))
  }

  private def canMessageAllUsers(userId: Id[User])(implicit s: RSession): Boolean = {
    experimentRepo.hasExperiment(userId, ExperimentTypes.CAN_MESSAGE_ALL_USERS)
  }

  private def getFriends(userId: Id[User]): Set[BasicUser] = {
    db.readOnly { implicit s =>
      if (canMessageAllUsers(userId)) {
        // TODO: remove this or find another way to do it in the future; this will not scale
        userRepo.allExcluding(UserStates.PENDING, UserStates.BLOCKED, UserStates.INACTIVE)
          .collect { case u if u.id.get != userId => BasicUser.fromUser(u) }.toSet
      } else {
        userConnectionRepo.getConnectedUsers(userId).map(basicUserRepo.load)
      }
    }
  }

  private case class UserPrefs(enterToSend: Boolean)
  private implicit val userPrefsFormat = Json.format[UserPrefs]

  private def loadUserPrefs(userId: Id[User]): UserPrefs = {
    db.readOnly { implicit s =>
      UserPrefs(
        enterToSend = userValueRepo.getValue(userId, "enter_to_send").map(_.toBoolean).getOrElse(true)
      )
    }
  }

  private def setAllNotificationsVisited(userId: Id[User], lastId: ExternalId[UserNotification]) {
    import UserNotificationCategories._
    import UserNotificationStates._
    db.readWrite { implicit s =>
      val lastNotification = userNotificationRepo.get(lastId)
      val excluded = Set(INACTIVE, SUBSUMED, VISITED)
      val notificationsToVisit = (if (excluded contains lastNotification.state) Set() else Set(lastNotification)) ++
          userNotificationRepo.getCreatedBefore(userId, lastNotification.createdAt, Integer.MAX_VALUE, excluded)

      for (notification <- notificationsToVisit) {
        notification.category match {
          case MESSAGE | COMMENT =>
            for (cid <- notification.commentId) {
              val comment = commentRepo.get(cid)
              notification.category match {
                case MESSAGE => setMessageRead(userId, comment, quietly = true)
                case _ => // when we add other types of notifications mark them read here
              }
            }
          case GLOBAL => userNotificationRepo.markVisited(userId, notification.externalId)
          case _ => // when we add other types of notifications mark them read here
        }
      }
      userChannel.pushAndFanout(userId, Json.arr("all_notifications_visited", lastId.id, lastNotification.createdAt))
    }
  }

  private def logEvent(session: StreamSession, o: JsObject) {
    val eventTime = clock.now.minusMillis((o \ "msAgo").asOpt[Int].getOrElse(0))
    val eventFamily = EventFamilies((o \ "eventFamily").as[String])
    val eventName = (o \ "eventName").as[String]
    val installId = (o \ "installId").as[String]
    val metaData = (o \ "metaData").asOpt[JsObject].getOrElse(Json.obj())
    val prevEvents = (o \ "prevEvents").asOpt[Seq[String]].getOrElse(Seq.empty).map(ExternalId[Event])
    val user = db.readOnly { implicit s => userRepo.get(session.userId) }
    val event = Events.userEvent(eventFamily, eventName, user, session.experiments, installId, metaData, prevEvents, eventTime)
    log.debug("Created new event: %s".format(event))
    eventPersister.persist(event)
  }

  private def getMessageThread(user: Id[User], messageId: ExternalId[Comment]): (NormalizedURI, Seq[CommentWithBasicUser]) = {
    db.readOnly { implicit session =>
      val message = commentRepo.get(messageId)
      val parent = message.parent.map(commentRepo.get).getOrElse(message)
      val messages = parent +: commentRepo.getChildren(parent.id.get) map commentWithBasicUserRepo.load

      //error message message always included with thread
      val SPECIAL_MESSAGE = "Hi, Kifi messages are down due to a system upgrade so your message was not sent. The upgrade should be finished this afternoon (PST). Check http://kifiupdates.tumblr.com/ for updates. Sorry for the inconvenience, and thanks for helping us build Kifi!"

      val bu = basicUserRepo.load(message.userId)
      val specialMessage = CommentWithBasicUser(
        id=ExternalId[Comment](),
        createdAt=currentDateTime,
        text=SPECIAL_MESSAGE,
        user=bu.copy(firstName="Kifi", lastName=""),
        permissions= CommentPermissions.MESSAGE,
        recipients=messages(0).recipients
      )



      (normUriRepo.get(parent.uriId), messages :+ specialMessage)
    }
  }

  private def setMessageRead(userId: Id[User], messageExtId: ExternalId[Comment]) {
    setMessageRead(userId, db.readOnly { implicit s => commentRepo.get(messageExtId) })
  }


  private def setGlobalRead(userId: Id[User], globalExtId: ExternalId[UserNotification]): Unit = {
    db.readWrite { implicit session =>
      userNotificationRepo.markVisited(userId, globalExtId)
    }
  }

  private def setMessageRead(userId: Id[User], message: Comment, quietly: Boolean = false) {
    db.readWrite { implicit session =>
      val parent = message.parent.map(commentRepo.get).getOrElse(message)
      commentReadRepo.getByUserAndParent(userId, parent.id.get) match {
        case Some(cr) if cr.lastReadId != message.id.get =>
          commentReadRepo.save(cr.withLastReadId(message.id.get))
        case None =>
          commentReadRepo.save(CommentRead(userId = userId, uriId = parent.uriId, parentId = parent.id, lastReadId = message.id.get))
        case _ =>
      }

      val nUri = normUriRepo.get(parent.uriId)
      if (!quietly) {
        userChannel.pushAndFanout(userId, Json.arr("message_read", nUri.url, parent.externalId.id, message.createdAt, message.externalId.id))
      }

      val messageIds = commentRepo.getMessageIdsCreatedBefore(nUri.id.get, parent.id.get, message.createdAt) :+ message.id.get
      userNotificationRepo.markCommentVisited(userId, messageIds)
    }
  }

  private def setKeeperPosition(userId: Id[User], host: String, pos: JsObject) {
    db.readWrite { implicit s =>
      val domain = domainRepo.get(host, excludeState = None) match {
        case Some(d) if d.state != DomainStates.ACTIVE => domainRepo.save(d.withState(DomainStates.ACTIVE))
        case Some(d) => d
        case None => domainRepo.save(Domain(hostname = host))
      }
      userToDomainRepo.get(userId, domain.id.get, UserToDomainKinds.KEEPER_POSITION, excludeState = None) match {
        case Some(p) if p.state != UserToDomainStates.ACTIVE || p.value.get != pos =>
          userToDomainRepo.save(p.withState(UserToDomainStates.ACTIVE).withValue(Some(pos)))
        case Some(p) => p
        case None =>
          userToDomainRepo.save(UserToDomain(None, userId, domain.id.get, UserToDomainKinds.KEEPER_POSITION, Some(pos)))
      }
    }
  }
}
