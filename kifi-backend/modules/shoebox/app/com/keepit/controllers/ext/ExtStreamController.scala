package com.keepit.controllers.ext

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise
import scala.util.Random

import org.joda.time.Seconds

import com.google.inject.Inject
import com.google.inject.Singleton
import com.keepit.classify.{Domain, DomainRepo, DomainStates}
import com.keepit.common.analytics._
import com.keepit.common.controller.FortyTwoCookies.ImpersonateCookie
import com.keepit.common.controller._
import com.keepit.common.db.ExternalId
import com.keepit.common.db.Id
import com.keepit.common.db.State
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.net.URINormalizer
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.social._
import com.keepit.common.time._
import com.keepit.controllers.core.KeeperInfoLoader
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

case class StreamSession(userId: Id[User], socialUser: SocialUserInfo, experiments: Set[State[ExperimentType]], adminUserId: Option[Id[User]])

@Singleton
class ExtStreamController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  socialUserInfoRepo: SocialUserInfoRepo,
  userConnectionRepo: UserConnectionRepo,
  socialConnectionRepo: SocialConnectionRepo,
  userRepo: UserRepo,
  basicUserRepo: BasicUserRepo,
  experimentRepo: UserExperimentRepo,
  userValueRepo: UserValueRepo,
  userChannel: UserChannel,
  uriChannel: UriChannel,
  userToDomainRepo: UserToDomainRepo,
  userNotificationRepo: UserNotificationRepo,
  EventPersister: EventPersister,
  keeperInfoLoader: KeeperInfoLoader,
  sliderRuleRepo: SliderRuleRepo,
  urlPatternRepo: URLPatternRepo,
  commentRepo: CommentRepo,
  commentReadRepo: CommentReadRepo,
  normUriRepo: NormalizedURIRepo,
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
      secSocialUser <- UserService.find(auth.userId)
    ) yield {

      val impersonatedUserIdOpt: Option[ExternalId[User]] =
        impersonateCookie.decodeFromCookie(request.cookies.get(impersonateCookie.COOKIE_NAME))

      db.readOnly { implicit session =>
        val socialUser = socialUserInfoRepo.get(SocialId(secSocialUser.id.id),
          SocialNetworkType(secSocialUser.id.providerId))
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

  def ws() = WebSocket.async[JsArray] { implicit request =>
    Akka.future {
      authenticate(request) match {
        case Some(streamSession) =>
          val connectedAt = clock.now
          val (enumerator, channel) = Concurrent.broadcast[JsArray]
          val socketId = Random.nextLong()
          val userId = streamSession.userId
          val subscriptions: TrieMap[String, Subscription] = TrieMap(
          "user" -> userChannel.subscribe(userId, socketId, channel))

          val socketAliveCancellable: Ref[Option[Cancellable]] = Ref(None.asInstanceOf[Option[Cancellable]])


          def endSession(reason: String) = {
            atomic { implicit txn =>
              socketAliveCancellable().map(c => if(!c.isCancelled) c.cancel())
            }
            log.info(s"Closing socket of userId ${streamSession.userId} because: $reason")
            channel.push(Json.arr("goodbye", reason))
            subscriptions.map(_._2.unsubscribe())
            subscriptions.clear()
            channel.eofAndEnd()
          }


          val handlers = Map[String, Seq[JsValue] => Unit](
            "ping" -> { _ =>
              channel.push(Json.arr("pong"))
            },
            "stats" -> { _ =>
              channel.push(Json.arr(s"id:$socketId", clock.now.minus(connectedAt.getMillis).getMillis / 1000.0, subscriptions.keys))
            },
            "normalize" -> { case JsNumber(requestId) +: JsString(url) +: _ =>
              channel.push(Json.arr(requestId.toLong, URINormalizer.normalize(url)))
            },
            "subscribe_uri" -> { case JsNumber(requestId) +: JsString(url) +: _ =>
              val nUri = URINormalizer.normalize(url)
              subscriptions.putIfAbsent(nUri, uriChannel.subscribe(nUri, socketId, channel))
              channel.push(Json.arr(requestId.toLong, nUri))
              channel.push(Json.arr("uri_1", nUri, keeperInfoLoader.load1(userId, nUri)))
              channel.push(Json.arr("uri_2", nUri, keeperInfoLoader.load2(userId, nUri)))
            },
            "unsubscribe_uri" -> { case JsString(url) +: _ =>
              val nUri = URINormalizer.normalize(url)
              subscriptions.get(nUri).foreach(_.unsubscribe())
              subscriptions.remove(nUri)
            },
            "log_event" -> { case JsObject(pairs) +: _ =>
              logEvent(streamSession, JsObject(pairs))
            },
            "get_rules" -> { case JsString(version) +: _ =>
              db.readOnly { implicit s =>
                val group = sliderRuleRepo.getGroup("default")
                if (version != group.version) {
                  channel.push(Json.arr("slider_rules", group.compactJson));
                  channel.push(Json.arr("url_patterns", urlPatternRepo.getActivePatterns()));
                }
              }
            },
            "get_friends" -> { _ =>
              channel.push(Json.arr("friends", getFriends(userId)))
            },
            "get_networks" -> { case JsNumber(requestId) +: JsString(friendExtId) +: _ =>
              channel.push(Json.arr(requestId, getNetworkInfo(userId, ExternalId(friendExtId))))
            },
            "get_thread" -> { case JsString(threadId) +: _ =>
              channel.push(Json.arr("thread", getMessageThread(ExternalId[Comment](threadId)) match { case (nUri, msgs) =>
                Json.obj("id" -> threadId, "uri" -> nUri.url, "messages" -> msgs)
              }))
            },
            "set_enter_to_send" -> { case JsBoolean(enterToSend) +: _ =>
              db.readWrite(implicit s => userValueRepo.setValue(userId, "enter_to_send", enterToSend.toString))
              channel.push(Json.arr("prefs", loadUserPrefs(userId)))
            },
            "get_prefs" -> { _ =>
              channel.push(Json.arr("prefs", loadUserPrefs(userId)))
            },
            "get_last_notify_read_time" -> { _ =>
              val t = db.readOnly(implicit s => userNotificationRepo.getLastReadTime(userId))
              channel.push(Json.arr("last_notify_read_time", t.toStandardTimeString))
            },
            "set_last_notify_read_time" -> { case JsString(time) +: _ =>
              val t = db.readWrite(implicit s => userNotificationRepo.setLastReadTime(userId, parseStandardTime(time)))
              channel.push(Json.arr("last_notify_read_time", t.toStandardTimeString))
            },
            "get_notifications" -> { case JsNumber(howMany) +: _ =>
              val (notices, unvisited) = db.readOnly { implicit s =>
                (userNotificationRepo.getLatestFor(userId, howMany.toInt),
                 userNotificationRepo.getUnvisitedCount(userId))
              }
              channel.push(Json.arr("notifications", notices.map(SendableNotification.fromUserNotification), unvisited))
            },
            "get_missed_notifications" -> { case JsString(time) +: _ =>
              val notices = db.readOnly(implicit s => userNotificationRepo.getCreatedAfter(userId, parseStandardTime(time)))
              channel.push(Json.arr("missed_notifications", notices.map(SendableNotification.fromUserNotification)))
            },
            "get_old_notifications" -> { case JsNumber(requestId) +: JsString(time) +: JsNumber(howMany) +: _ =>
              val notices = db.readOnly(implicit s => userNotificationRepo.getCreatedBefore(userId, parseStandardTime(time), howMany.toInt))
              channel.push(Json.arr(requestId.toLong, notices.map(SendableNotification.fromUserNotification)))
            },
            "set_all_notifications_visited" -> { case JsString(notifId) +: _ =>
              setAllNotificationsVisited(userId, ExternalId[UserNotification](notifId))
            },
            "set_message_read" -> { case JsString(messageId) +: _ =>
              setMessageRead(userId, ExternalId[Comment](messageId))
            },
            "set_comment_read" -> { case JsString(commentId) +: _ =>
              setCommentRead(userId, ExternalId[Comment](commentId))
            },
            "set_global_read" -> { case JsString(commentId) +: _ =>
              setGlobalRead(userId, ExternalId[UserNotification](commentId))
            },
            "set_keeper_position" -> { case JsString(host) +: JsObject(pos) +: _ =>
              setKeeperPosition(userId, host, JsObject(pos))
            })

          val iteratee = asyncIteratee { jsArr =>
            Option(jsArr.value(0)).flatMap(_.asOpt[String]).flatMap(handlers.get).map { handler =>
              atomic { implicit txn =>
                socketAliveCancellable().map(c => if(!c.isCancelled) c.cancel())
                socketAliveCancellable.single.swap {
                  import scala.concurrent.duration._
                  val c = system.scheduler.scheduleOnce(65.seconds) {
                    log.info(s"It seems like userId ${streamSession.userId}'s socket is stale.")
                  }
                  Some(c)
                }
              }

              Statsd.increment(s"websocket.handler.${jsArr.value(0)}")
              Statsd.time(s"websocket.handler.${jsArr.value(0)}") {handler(jsArr.value.tail)}
            } getOrElse {
              log.warn("WS no handler for: " + jsArr)
            }
          }.mapDone(_ => endSession("Session ended"))

          (iteratee, Enumerator(Json.arr("hi")) >>> enumerator)

        case None =>
          Statsd.increment(s"websocket.anonymous")
          log.info("Disconnecting anonymous user")
          (Iteratee.ignore, Enumerator(Json.arr("denied")) >>> Enumerator.eof)
      }
    }
  }

  private case class NetworkInfo(profileUrl: Option[String], connected: Boolean)
  private implicit val writesNetworkInfo = Json.writes[NetworkInfo]
  private implicit val writesNetworkTypeToNetworkInfo = new Writes[Map[SocialNetworkType, NetworkInfo]] {
    def writes(o: Map[SocialNetworkType, NetworkInfo]): JsValue =
      JsObject(o.map { case (network, info) => network.name -> Json.toJson(info) }.toSeq)
  }

  private def getNetworkInfo(userId: Id[User], friendId: ExternalId[User]): Map[SocialNetworkType, NetworkInfo] =
    db.readOnly { implicit s =>
      val mySocialUsers = socialUserInfoRepo.getByUser(userId)
      for {
        friend <- userRepo.getOpt(friendId).toSeq
        su <- socialUserInfoRepo.getByUser(friend.id.get)
      } yield {
        su.networkType -> NetworkInfo(
          profileUrl = su.getProfileUrl,
          connected = mySocialUsers.exists { mySu =>
            mySu.networkType == su.networkType &&
                socialConnectionRepo.getConnectionOpt(su.id.get, mySu.id.get).isDefined
          }
        )
      }
    }.toMap

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
                case COMMENT => setCommentRead(userId, comment, quietly = true)
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
    EventPersister.persist(event)
  }

  private def getMessageThread(messageId: ExternalId[Comment]): (NormalizedURI, Seq[CommentWithBasicUser]) = {
    db.readOnly { implicit session =>
      val message = commentRepo.get(messageId)
      val parent = message.parent.map(commentRepo.get).getOrElse(message)
      val messages = parent +: commentRepo.getChildren(parent.id.get) map commentWithBasicUserRepo.load
      (normUriRepo.get(parent.uriId), messages)
    }
  }

  private def setMessageRead(userId: Id[User], messageExtId: ExternalId[Comment]) {
    setMessageRead(userId, db.readOnly { implicit s => commentRepo.get(messageExtId) })
  }

  private def setCommentRead(userId: Id[User], commentExtId: ExternalId[Comment]) {
    setCommentRead(userId, db.readOnly { implicit s => commentRepo.get(commentExtId) })
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

  private def setCommentRead(userId: Id[User], comment: Comment, quietly: Boolean = false) {
    db.readWrite { implicit session =>
      (commentReadRepo.getByUserAndUri(userId, comment.uriId) match {
        case Some(cr) if cr.lastReadId != comment.id.get =>
          Some(commentReadRepo.save(cr.withLastReadId(comment.id.get)))
        case None =>
          Some(commentReadRepo.save(CommentRead(userId = userId, uriId = comment.uriId, lastReadId = comment.id.get)))
        case _ => None
      }) foreach { _ =>
        val nUri = normUriRepo.get(comment.uriId)

        if (!quietly) {
          userChannel.pushAndFanout(userId, Json.arr("comment_read", nUri.url, comment.createdAt, comment.externalId.id))
        }

        val commentIds = commentRepo.getPublicIdsCreatedBefore(nUri.id.get, comment.createdAt) :+ comment.id.get
        userNotificationRepo.markCommentVisited(userId, commentIds)
      }
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
