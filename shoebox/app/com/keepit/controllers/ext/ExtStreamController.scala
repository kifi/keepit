package com.keepit.controllers.ext

import com.keepit.common.controller._
import com.keepit.common.controller.FortyTwoController.ImpersonateCookie
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.net.URINormalizer
import com.keepit.common.social._
import com.keepit.common.time.Clock
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
import com.keepit.controllers.core.PaneDetails
import com.keepit.serializer.UserWithSocialSerializer.userWithSocialSerializer
import com.keepit.serializer.CommentWithSocialUserSerializer.commentWithSocialUserSerializer
import com.keepit.serializer.ThreadInfoSerializer.threadInfoSerializer

case class StreamSession(userId: Id[User], socialUser: SocialUserInfo, experiments: Seq[State[ExperimentType]], adminUserId: Option[Id[User]])

@Singleton
class ExtStreamController @Inject() (
  db: Database,
  socialUserInfoRepo: SocialUserInfoRepo,
  userRepo: UserRepo,
  experimentRepo: UserExperimentRepo,
  userChannel: UserChannel,
  uriChannel: UriChannel,
  clock: Clock,
  paneData: PaneDetails) extends BrowserExtensionController with ShoeboxServiceController {
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

  def ws() = WebSocket.using[JsArray] { implicit request =>
    authenticate(request) match {
      case Some(streamSession) =>

        val connectedAt = clock.now
        val (enumerator, channel) = Concurrent.broadcast[JsArray]
        val socketId = Random.nextLong()

        var subscriptions = Map[String, Subscription]()

        subscriptions += (("user", userChannel.subscribe(streamSession.userId, socketId, channel)))

        val iteratee = Iteratee.foreach[JsArray] {
          _.as[Seq[JsValue]] match {
            case JsString("ping") +: _ =>
              channel.push(Json.arr("pong"))
            case JsString("stats") +: _ =>
              channel.push(Json.arr(s"id:$socketId", clock.now.minus(connectedAt.getMillis).getMillis / 1000.0, subscriptions.keys))
            case JsString("normalize") +: JsNumber(requestId) +: JsString(url) +: _ =>
              channel.push(Json.arr(requestId.toLong, URINormalizer.normalize(url)))
            case JsString("subscribe") +: sub =>
              subscriptions = subscribe(streamSession, socketId, channel, subscriptions, sub)
            case JsString("unsubscribe") +: unsub =>
              subscriptions = unsubscribe(streamSession, socketId, channel, subscriptions, unsub)
            case JsString("get_comments") +: JsNumber(requestId) +: JsString(url) +: _ =>
              channel.push(Json.arr(requestId.toLong, paneData.getComments(streamSession.userId, url)))
            case JsString("get_message_threads") +: JsNumber(requestId) +: JsString(url) +: _ =>
              channel.push(Json.arr(requestId.toLong, paneData.getMessageThreadList(streamSession.userId, url)))
            case JsString("get_message_thread") +: JsNumber(requestId) +: JsString(threadId) +: _ =>
              channel.push(Json.arr(requestId.toLong, paneData.getMessageThread(streamSession.userId, ExternalId[Comment](threadId))))
            case json =>
              log.warn(s"Not sure what to do with: $json")
          }
        }.mapDone { _ =>
          subscriptions.map(_._2.unsubscribe)
        }

        (iteratee, enumerator)

      case None =>
        log.info(s"Anonymous user trying to connect. Disconnecting!")
        val enumerator: Enumerator[JsArray] = Enumerator(Json.arr("error", "Permission denied. Are you logged in? Connect again to re-authenticate."))
        val iteratee = Iteratee.ignore[JsArray]

        (iteratee, enumerator >>> Enumerator.eof)
    }
  }

  private def subscribe(session: StreamSession, socketId: Long, channel: PlayChannel[JsArray], subscriptions: Map[String, Subscription], sub: Seq[JsValue]): Map[String, Subscription] = {
    sub match {
      case JsString(sub) +: _ if subscriptions.contains(sub) =>
        subscriptions
      case JsString("user") +: _ =>
        val sub = userChannel.subscribe(session.userId, socketId, channel)
        channel.push(Json.arr("subscribed", "user"))
        subscriptions + (("user", sub))
      case JsString("uri") +: JsString(url) +: _ => // todo
        val normalized = URINormalizer.normalize(url)
        val sub = uriChannel.subscribe(normalized, socketId, channel)
        channel.push(Json.arr("subscribed", sub.name))
        subscriptions + ((sub.name, sub))
      case json =>
        log.warn(s"Can't subscribe to $json. No handler.")
        channel.push(Json.arr("subscribed_error", "Can't subscribe, no handler."))
        subscriptions
    }
  }

  private def unsubscribe(session: StreamSession, socketId: Long, channel: PlayChannel[JsArray], subscriptions: Map[String, Subscription], subParams: Seq[JsValue]): Map[String, Subscription] = {
    subParams match {
      case JsString(sub) +: _ if subscriptions.contains(sub) =>
        // For all subscriptions with an id
        val subscription = subscriptions.get(sub).get
        channel.push(Json.arr("unsubscribed", sub))
        subscription.unsubscribe()
        subscriptions - sub
      case JsString("uri") +: JsString(url) +: _ if subscriptions.contains(s"uri:${URINormalizer.normalize(url)}") =>
        // Handled separately because of URL normalization
        val name = s"uri:${URINormalizer.normalize(url)}"
        val subscription = subscriptions.get(name).get
        channel.push(Json.arr("unsubscribed", name))
        subscription.unsubscribe()
        subscriptions - name
      case JsString(sub) +: JsString(id) +: _ if subscriptions.contains(s"$sub:$id") =>
        // For all subscriptions with an id and sub-id
        val name = s"$sub:$id"
        val subscription = subscriptions.get(name).get
        channel.push(Json.arr("unsubscribed", name))
        subscription.unsubscribe()
        subscriptions - name
      case JsString(sub) +: _ =>
        channel.push(Json.arr("unsubscribed_error", s"Not currently subscribed to $sub"))
        subscriptions
      case json =>
        log.warn(s"Can't unsubscribe from $json. No handler.")
        subscriptions
    }
  }

}

