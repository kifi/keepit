package com.keepit.controllers.ext

import com.keepit.common.analytics._
import com.keepit.common.controller._
import com.keepit.common.controller.FortyTwoCookies.ImpersonateCookie
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.net.URINormalizer
import com.keepit.common.social._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.realtime._
import java.util.UUID
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.google.inject.Singleton
import play.api.libs.iteratee.Concurrent
import play.api.libs.iteratee.Concurrent.{ Channel => PlayChannel }
import play.api.libs.iteratee.Enumerator
import play.api.libs.iteratee.Iteratee
import play.api.libs.json._
import play.api.libs.json.Json
import play.api.mvc.RequestHeader
import play.api.mvc.WebSocket
import play.api.mvc.WebSocket.FrameFormatter
import securesocial.core._
import com.keepit.common.db.Id
import com.keepit.common.db.State
import scala.util.Random
import com.keepit.controllers.core.{PaneDetails, KeeperInfoLoader}
import com.keepit.serializer.BasicUserSerializer.basicUserSerializer
import com.keepit.serializer.UserWithSocialSerializer.userWithSocialSerializer
import com.keepit.serializer.CommentWithBasicUserSerializer.commentWithBasicUserSerializer
import com.keepit.serializer.ThreadInfoSerializer.threadInfoSerializer
import com.keepit.serializer.SendableNotificationSerializer.sendableNotificationSerializer
import org.joda.time.DateTime
import play.api.libs.concurrent.Akka
import play.api.Play.current
import com.keepit.common.controller.FortyTwoServices

case class StreamSession(userId: Id[User], socialUser: SocialUserInfo, experiments: Seq[State[ExperimentType]], adminUserId: Option[Id[User]])

@Singleton
class ExtStreamController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  socialUserInfoRepo: SocialUserInfoRepo,
  socialConnectionRepo: SocialConnectionRepo,
  userRepo: UserRepo,
  basicUserRepo: BasicUserRepo,
  experimentRepo: UserExperimentRepo,
  userChannel: UserChannel,
  uriChannel: UriChannel,
  userNotificationRepo: UserNotificationRepo,
  persistEventPlugin: PersistEventPlugin,
  keeperInfoLoader: KeeperInfoLoader,
  sliderRuleRepo: SliderRuleRepo,
  urlPatternRepo: URLPatternRepo,
  commentRepo: CommentRepo,
  commentReadRepo: CommentReadRepo,
  normUriRepo: NormalizedURIRepo,
  paneData: PaneDetails,
  eventHelper: EventHelper,
  implicit private val clock: Clock,
  implicit private val fortyTwoServices: FortyTwoServices)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {
  private def authenticate(request: RequestHeader): Option[StreamSession] = {
    /*
     * Unfortunately, everything related to existing secured actions intimately deals with Action, Request, Result, etc.
     * WebSockets cannot use these, so I've implemented what I need below.
     */
    for (
      userId <- request.session.get(SecureSocial.UserKey);
      providerId <- request.session.get(SecureSocial.ProviderKey);
      secSocialUser <- UserService.find(UserId(userId, providerId))
    ) yield {
      val impersonatedUserIdOpt: Option[ExternalId[User]] = ImpersonateCookie.decodeFromCookie(request.cookies.get(ImpersonateCookie.COOKIE_NAME))

      db.readOnly { implicit session =>
        val socialUser = socialUserInfoRepo.get(SocialId(secSocialUser.id.id), SocialNetworks.FACEBOOK)
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
          var subscriptions: Map[String, Subscription] = Map(
            "user" -> userChannel.subscribe(userId, socketId, channel))

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
              subscriptions = subscriptions + (nUri -> uriChannel.subscribe(nUri, socketId, channel))
              channel.push(Json.arr(requestId.toLong, nUri))
              channel.push(Json.arr("uri_1", nUri, keeperInfoLoader.load1(userId, nUri)))
              channel.push(Json.arr("uri_2", nUri, keeperInfoLoader.load2(userId, nUri)))
              },
            "unsubscribe_uri" -> { case JsString(url) +: _ =>
              val nUri = URINormalizer.normalize(url)
              subscriptions.get(nUri).foreach(_.unsubscribe())
              subscriptions = subscriptions - nUri
            },
            "log_event" -> { case JsObject(pairs) +: _ =>
              logEvent(streamSession, JsObject(pairs))
            },
            "get_friends" -> { _ =>
              channel.push(Json.arr("friends", getFriends(userId)))
            },
            "get_comments" -> { case JsNumber(requestId) +: JsString(url) +: _ =>// unused, remove soon
              channel.push(Json.arr(requestId.toLong, paneData.getComments(userId, url)))
            },
            "get_message_threads" -> { case JsNumber(requestId) +: JsString(url) +: _ =>     // unused, remove soon
              channel.push(Json.arr(requestId.toLong, paneData.getMessageThreadList(userId, url)))
            },
            "get_message_thread" -> { case JsNumber(requestId) +: JsString(threadId) +: _ =>  // unused, remove soon
              channel.push(Json.arr(requestId.toLong, paneData.getMessageThread(userId, ExternalId[Comment](threadId))))
            },
            "get_thread" -> { case JsString(threadId) +: _ =>
              channel.push(Json.arr("thread", paneData.getMessageThread(ExternalId[Comment](threadId)) match { case (nUri, msgs) =>
                Json.obj("id" -> threadId, "uri" -> nUri.url, "messages" -> msgs)
              }))
            },
            "get_last_notify_read_time" -> { _ =>
              channel.push(Json.arr("last_notify_read_time", getLastNotifyTime(userId).toStandardTimeString))
            },
            "set_last_notify_read_time" -> { data =>
              val time = data match {
                case JsString(dateTime) +: _ => parseStandardTime(dateTime)
                case _ => clock.now
              }
              channel.push(Json.arr("last_notify_read_time", setLastNotifyTime(userId, time).toStandardTimeString))
            },
            "get_notifications" -> { case JsNumber(howMany) +: params =>
              val createdBefore = params match {
                case JsString(time) +: _ => Some(parseStandardTime(time))
                case _ => None
              }
              val toFetch =
                if (createdBefore.isEmpty)
                  math.max(db.readOnly(implicit s => userNotificationRepo.getUnreadCount(userId)), howMany.toInt)
                else howMany.toInt
              channel.push(Json.arr("notifications", getNotifications(userId, createdBefore, toFetch)))
            },
            "set_message_read" -> { case JsString(messageId) +: _ =>
              setMessageRead(userId, ExternalId[Comment](messageId))
            },
            "set_comment_read" -> { case JsString(commentId) +: _ =>
              setCommentRead(userId, ExternalId[Comment](commentId))
            })

          val iteratee = asyncIteratee { jsArr =>
            log.info("WS just received: " + jsArr)
            Option(jsArr.value(0)).flatMap(_.asOpt[String]).flatMap(handlers.get).map { handler =>
              handler(jsArr.value.tail)
            } getOrElse {
              log.warn("WS no handler for: " + jsArr)
            }
          }.mapDone { _ =>
            subscriptions.map(_._2.unsubscribe)
            subscriptions = Map.empty
          }

          (iteratee, enumerator)

        case None =>
          log.info("Disconnecting anonymous user")
          (Iteratee.ignore, Enumerator(Json.arr("denied")) >>> Enumerator.eof)
      }
    }
  }

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

  private def getFriends(userId: Id[User]): Set[BasicUser] = {
    db.readOnly { implicit s =>
      socialConnectionRepo.getFortyTwoUserConnections(userId).map(basicUserRepo.load)
    }
  }

  private def getLastNotifyTime(userId: Id[User]): DateTime = {
    db.readOnly(implicit s => userNotificationRepo.getLastReadTime(userId))
  }

  private def setLastNotifyTime(userId: Id[User], time: DateTime): DateTime = {
    db.readWrite(implicit s => userNotificationRepo.setLastReadTime(userId, time))
  }

  private def getNotifications(userId: Id[User], createdBefore: Option[DateTime], howMany: Int): Seq[SendableNotification] = {
    db.readOnly(implicit s => userNotificationRepo.getWithUserId(userId, createdBefore, howMany)).map(n => SendableNotification.fromUserNotification(n))
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
    persistEventPlugin.persist(event)
    eventHelper.newEvent(event)
  }

  private def setMessageRead(userId: Id[User], messageExtId: ExternalId[Comment]) {
    db.readWrite { implicit session =>
      val message = commentRepo.get(messageExtId)
      val parent = message.parent.map(commentRepo.get).getOrElse(message)
      (commentReadRepo.getByUserAndParent(userId, parent.id.get) match {
        case Some(cr) if cr.lastReadId != message.id.get =>
          Some(commentReadRepo.save(cr.withLastReadId(message.id.get)))
        case None =>
          Some(commentReadRepo.save(CommentRead(userId = userId, uriId = parent.uriId, parentId = parent.id, lastReadId = message.id.get)))
        case _ => None
      }) foreach { _ =>
        val nUri = normUriRepo.get(parent.uriId)
        userChannel.push(userId, Json.arr("message_read", nUri.url, parent.externalId.id, message.createdAt))
        userNotificationRepo.getWithCommentId(userId, message.id.get) foreach { n =>
          val vn = userNotificationRepo.save(n.withState(UserNotificationStates.VISITED))
          userChannel.push(userId, Json.arr("notifications", Seq(SendableNotification.fromUserNotification(vn))))
        }
      }
    }
  }

  private def setCommentRead(userId: Id[User], commentExtId: ExternalId[Comment]) {
    db.readWrite { implicit session =>
      val comment = commentRepo.get(commentExtId)
      (commentReadRepo.getByUserAndUri(userId, comment.uriId) match {
        case Some(cr) if cr.lastReadId != comment.id.get =>
          Some(commentReadRepo.save(cr.withLastReadId(comment.id.get)))
        case None =>
          Some(commentReadRepo.save(CommentRead(userId = userId, uriId = comment.uriId, lastReadId = comment.id.get)))
        case _ => None
      }) foreach { _ =>
        val nUri = normUriRepo.get(comment.uriId)
        userChannel.push(userId, Json.arr("comment_read", nUri.url, comment.createdAt))

        val commentIds = commentRepo.getPublicIdsCreatedBefore(nUri.id.get, comment.createdAt) :+ comment.id.get
        val notifications = userNotificationRepo.getWithCommentIds(userId, commentIds, setCommentReadExcludeStates) map { n =>
          userNotificationRepo.save(n.withState(UserNotificationStates.VISITED))
        }
        userChannel.push(userId, Json.arr("notifications", notifications map SendableNotification.fromUserNotification))
      }
    }
  }
  private val setCommentReadExcludeStates = Set(
    UserNotificationStates.INACTIVE,
    UserNotificationStates.VISITED,
    UserNotificationStates.SUBSUMED)

}
