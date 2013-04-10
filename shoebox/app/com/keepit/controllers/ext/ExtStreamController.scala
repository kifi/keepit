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
  userRepo: UserRepo,
  experimentRepo: UserExperimentRepo,
  userChannel: UserChannel,
  uriChannel: UriChannel,
  userNotificationRepo: UserNotificationRepo,
  persistEventPlugin: PersistEventPlugin,
  keeperInfoLoader: KeeperInfoLoader,
  sliderRuleRepo: SliderRuleRepo,
  urlPatternRepo: URLPatternRepo,
  commentRepo: CommentRepo,
  paneData: PaneDetails,
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

          val iteratee = asyncIteratee { json =>
            log.info("WS just received: " + json)
            json.as[Seq[JsValue]] match {
              case JsString("ping") +: _ =>
                channel.push(Json.arr("pong"))
              case JsString("stats") +: _ =>
                channel.push(Json.arr(s"id:$socketId", clock.now.minus(connectedAt.getMillis).getMillis / 1000.0, subscriptions.keys))
              case JsString("normalize") +: JsNumber(requestId) +: JsString(url) +: _ =>
                channel.push(Json.arr(requestId.toLong, URINormalizer.normalize(url)))
              case JsString("subscribe_uri") +: JsNumber(requestId) +: JsString(url) +: _ =>
                val nUri = URINormalizer.normalize(url)
                subscriptions = subscriptions + (nUri -> uriChannel.subscribe(nUri, socketId, channel))
                channel.push(Json.arr(requestId.toLong, nUri))
                channel.push(Json.arr("uri_1", nUri, keeperInfoLoader.load1(userId, nUri)))
                channel.push(Json.arr("uri_2", nUri, keeperInfoLoader.load2(userId, nUri)))
              case JsString("unsubscribe_uri") +: JsString(url) +: _ =>
                val nUri = URINormalizer.normalize(url)
                subscriptions.get(nUri).foreach(_.unsubscribe())
                subscriptions = subscriptions - nUri
              case JsString("log_event") +: JsObject(pairs) +: _ =>
                logEvent(streamSession, JsObject(pairs))
              case JsString("get_comments") +: JsNumber(requestId) +: JsString(url) +: _ =>
                channel.push(Json.arr(requestId.toLong, paneData.getComments(userId, url)))
                // channel.push(Json.arr(requestId.toLong, commentWithBasicUserSerializer.writes(paneData.getComments(userId, url))))
              case JsString("get_message_threads") +: JsNumber(requestId) +: JsString(url) +: _ =>
                channel.push(Json.arr(requestId.toLong, paneData.getMessageThreadList(userId, url)))
              case JsString("get_message_thread") +: JsNumber(requestId) +: JsString(threadId) +: _ =>
                channel.push(Json.arr(requestId.toLong, paneData.getMessageThread(userId, ExternalId[Comment](threadId))))
              case JsString("get_last_notify_read_time") +: _ =>
                channel.push(Json.arr("last_notify_read_time", getLastNotifyTime(userId).toString()))
              case JsString("set_last_notify_read_time") +: _ =>
                channel.push(Json.arr("last_notify_read_time", setLastNotifyTime(userId).toString()))
              case JsString("get_notifications") +: JsNumber(howMany) +: params =>
                val createdBefore = params match {
                  case JsString(time) +: _ => Some(parseStandardTime(time))
                  case _ => None
                }
                channel.push(Json.arr("notifications", getNotifications(userId, createdBefore, howMany.toInt)))
              case JsString("set_message_read") +: JsString(parentExternalId) +: _ =>
                setMessageRead(userId, parentExternalId)
              case JsString("set_comment_read") +: JsString(externalId) +: _ =>
                setCommentRead(userId, externalId)
              case json =>
                log.warn(s"Not sure what to do with: $json")
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

  private def asyncIteratee(f: JsValue => Unit): Iteratee[JsArray, Unit] = {
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

  private def getLastNotifyTime(userId: Id[User]): DateTime = {
    db.readOnly(implicit s => userNotificationRepo.getLastReadTime(userId))
  }

  private def setLastNotifyTime(userId: Id[User]): DateTime = {
    db.readWrite(implicit s => userNotificationRepo.setLastReadTime(userId))
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
  }

  private def setMessageRead(userId: Id[User], externalId: String) = {
    db.readWrite { implicit session =>
      val comment = commentRepo.get(ExternalId[Comment](externalId))
      val parentId = comment.parent.map(p => commentRepo.get(p).id.get).getOrElse(comment.id.get)
      userNotificationRepo.getWithCommentId(userId, parentId).foreach { n =>
        userNotificationRepo.save(n.withState(UserNotificationStates.VISITED))
      }
    }
  }

  private def setCommentRead(userId: Id[User], externalId: String) = {
    db.readWrite { implicit session =>
      val commentId = commentRepo.get(ExternalId[Comment](externalId)).id.get
      userNotificationRepo.getWithCommentId(userId, commentId).foreach { n =>
        userNotificationRepo.save(n.withState(UserNotificationStates.VISITED))
      }
    }
  }

}
