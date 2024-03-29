package com.keepit.eliza.controllers

import com.keepit.common.strings._
import com.keepit.common.controller.{ UserActionsHelper, KifiSession, ElizaServiceController }
import com.keepit.common.db.Id
import com.keepit.model.{ UserExperimentType, KifiVersion, KifiExtVersion, KifiIPhoneVersion, KifiAndroidVersion, User, SocialUserInfo }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.shoebox.model.ids.UserSessionExternalId
import com.keepit.social.SocialNetworkType
import com.keepit.common.controller.FortyTwoCookies.ImpersonateCookie
import com.keepit.common.time._
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import com.keepit.heimdal._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.crypto.RatherInsecureDESCrypt
import com.keepit.commanders.RemoteUserExperimentCommander
import scala.util.Try

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import scala.util.Random

import play.api.mvc.{ WebSocket, RequestHeader }
import play.api.libs.iteratee.{ Enumerator, Iteratee, Concurrent }
import play.api.mvc.WebSocket.FrameFormatter
import play.api.libs.json.{ Json, JsValue }

import akka.actor.ActorSystem

import securesocial.core.{ IdentityId, Authenticator, UserService, SecureSocial }

import org.joda.time.DateTime
import play.api.libs.json.JsArray
import com.keepit.social.SocialId
import com.keepit.common.net.{ HttpClient, DirectUrl, UserAgent }
import com.keepit.common.store.KifiInstallationStore
import com.keepit.common.logging.{ AccessLogTimer, AccessLog }
import com.keepit.common.logging.Access.WS_IN
import org.apache.commons.lang3.RandomStringUtils

case class StreamSession(userId: Id[User], experiments: Set[UserExperimentType], adminUserId: Option[Id[User]], userAgent: String)

case class SocketInfo(
  channel: Concurrent.Channel[JsArray],
  connectedAt: DateTime,
  userId: Id[User],
  experiments: Set[UserExperimentType],
  kifiVersion: Option[KifiVersion],
  userAgent: String,
  var ip: Option[String],
  id: Long = Random.nextLong(),
  trackingId: String = RandomStringUtils.randomAlphanumeric(5))

trait AuthenticatedWebSocketsController extends ElizaServiceController {

  protected val shoebox: ShoeboxServiceClient
  protected val impersonateCookie: ImpersonateCookie
  protected val actorSystem: ActorSystem
  protected val clock: Clock
  protected val airbrake: AirbrakeNotifier
  protected val heimdal: HeimdalServiceClient
  protected val heimdalContextBuilder: HeimdalContextBuilderFactory
  protected val userExperimentCommander: RemoteUserExperimentCommander
  protected val websocketRouter: WebSocketRouter
  protected val shoutdownListener: WebsocketsShutdownListener

  val userActionsHelper: UserActionsHelper
  val kifInstallationStore: KifiInstallationStore
  val accessLog: AccessLog

  protected def onConnect(socket: SocketInfo): Unit
  protected def onDisconnect(socket: SocketInfo): Unit
  protected def websocketHandlers(socket: SocketInfo): Map[String, Seq[JsValue] => Unit]

  protected val crypt = new RatherInsecureDESCrypt
  protected val ipkey = crypt.stringToKey("dontshowtheiptotheclient")

  private def asyncIteratee(streamSession: StreamSession, kifiVersionOpt: Option[String])(f: JsArray => Unit): Iteratee[JsArray, Unit] = {
    val kifiVersion = kifiVersionOpt.getOrElse("N/A")
    import play.api.libs.iteratee._
    def step(i: Input[JsArray]): Iteratee[JsArray, Unit] = i match {
      case Input.EOF => Done(Unit, Input.EOF)
      case Input.Empty => Cont[JsArray, Unit](i => step(i))
      case Input.El(e) =>
        SafeFuture {
          try {
            f(e)
          } catch {
            case ex: Throwable => airbrake.notify(
              AirbrakeError(
                exception = ex,
                method = Some("ws"),
                url = e.value.headOption.map(_.toString()),
                message = Some(s"[WS] user ${streamSession.userId.id} using version $kifiVersion on ${streamSession.userAgent.abbreviate(30)} making call ${e.toString()}")
              )
            )
          }
        }
        Cont[JsArray, Unit](i => step(i))
    }
    Cont[JsArray, Unit](i => step(i))
  }

  implicit val jsonFrame: FrameFormatter[JsArray] = {
    FrameFormatter.stringFrame.transform(
      out => {
        Json.stringify(out)
      },
      in => {
        Json.parse(in) match {
          case j: JsArray => j
          case j: JsValue => Json.arr(j)
        }
      })
  }

  private def authenticate(implicit request: RequestHeader): Future[Option[StreamSession]] = {
    userActionsHelper.getUserIdOptWithFallback(request).flatMap { userIdOpt =>
      userIdOpt.map { userId =>
        userExperimentCommander.getExperimentsByUser(userId).flatMap { experiments =>
          val userAgent = request.headers.get("User-Agent").getOrElse("NA")
          if (experiments.contains(UserExperimentType.ADMIN)) {
            impersonateCookie.decodeFromCookie(request.cookies.get(impersonateCookie.COOKIE_NAME)) map { impExtUserId =>
              for {
                impUserId <- shoebox.getUserOpt(impExtUserId).map(_.get.id.get)
              } yield {
                StreamSession(impUserId, experiments, Some(userId), userAgent)
              }
            } getOrElse {
              Future.successful(StreamSession(userId, experiments, Some(userId), userAgent))
            }
          } else {
            Future.successful(StreamSession(userId, experiments, None, userAgent))
          }
        }.map { session =>
          Some(session): Option[StreamSession]
        }
      }.getOrElse {
        Future.successful(None)
      }
    }
  }

  // see https://github.com/kifi/keepit/pull/10867
  def websocket(versionOpt: Option[String], eipOpt: Option[String]) = WebSocket.tryAccept[JsArray] { implicit request =>
    val connectTimer = accessLog.timer(WS_IN)
    if (shoutdownListener.shuttingDown) {
      Future.successful {
        accessLog.add(connectTimer.done(trackingId = "xxxxx", method = "DISCONNECT", body = "refuse connect"))
        Right((Iteratee.ignore, Enumerator(Json.arr("bye", "shutdown")) >>> Enumerator.eof))
      }
    } else {
      val iterateeAndEnumeratorFuture = authenticate(request).map { streamSessionOpt =>
        //type inference fail
        val response: (Iteratee[JsArray, Unit], Enumerator[JsArray]) = streamSessionOpt.map { streamSession =>
          implicit val (enumerator, channel) = Concurrent.broadcast[JsArray]

          val typedVersionOpt: Option[KifiVersion] = try {
            UserAgent(streamSession.userAgent) match {
              case ua if ua.isKifiIphoneApp || versionOpt.exists(_.startsWith("m")) => versionOpt.map { v => KifiIPhoneVersion(v.stripPrefix("m")) }
              case ua if ua.isKifiAndroidApp => versionOpt.map(KifiAndroidVersion.apply)
              case _ => versionOpt.map(KifiExtVersion.apply)
            }
          } catch {
            case t: Throwable =>
              airbrake.notify(s"Failed getting ws client version for user ${streamSession.userId} on ${streamSession.userAgent}", t)
              None
          }

          val ipOpt: Option[String] = eipOpt.flatMap { eip =>
            crypt.decrypt(ipkey, eip).toOption
          }
          val socketInfo = SocketInfo(channel, clock.now, streamSession.userId, streamSession.experiments, typedVersionOpt, streamSession.userAgent, ipOpt)
          reportConnect(streamSession, socketInfo, request, connectTimer)
          var startMessages = Seq[JsArray](Json.arr("hi"))
          if (updateNeeded(typedVersionOpt, streamSession.userId)) {
            startMessages = startMessages :+ Json.arr("version", "new")
          }
          onConnect(socketInfo)
          val finalEnum = Enumerator(startMessages: _*) >>> enumerator
          (iteratee(streamSession, versionOpt, socketInfo, channel), finalEnum)
        }.getOrElse {
          log.info(s"[websocket] Denied client because no user found: ${request.headers.toSimpleMap.toString}")
          (Iteratee.ignore, Enumerator(Json.arr("denied")) >>> Enumerator.eof)
        }
        response
      }
      iterateeAndEnumeratorFuture.onFailure {
        case t: Throwable =>
          airbrake.notify("Fatal error when establishing websocket connection", t)
      }
      iterateeAndEnumeratorFuture.map(Right(_))
    }
  }

  private def reportConnect(streamSession: StreamSession, socketInfo: SocketInfo, request: RequestHeader, connectTimer: AccessLogTimer): Unit = {
    val tStart = currentDateTime
    //Analytics
    SafeFuture {
      val context = authenticatedWebSocketsContextBuilder(socketInfo, Some(request)).build
      val lastTracked = websocketRouter.socketLastTracked(socketInfo.userId)
      if (lastTracked.isEmpty || lastTracked.get.isBefore(currentDateTime.minusHours(8))) {
        websocketRouter.setSocketLastTracked(socketInfo.userId)
        heimdal.trackEvent(UserEvent(socketInfo.userId, context, UserEventTypes.CONNECTED, tStart))
      }
      heimdal.setUserProperties(socketInfo.userId, "lastConnected" -> ContextDate(tStart))
    }
    accessLog.add(connectTimer.done(trackingId = socketInfo.trackingId, method = "CONNECT"))
  }

  private def iteratee(streamSession: StreamSession, versionOpt: Option[String], socketInfo: SocketInfo, channel: Concurrent.Channel[JsArray]) = {
    val handlers = websocketHandlers(socketInfo)
    val agentFamily = UserAgent(streamSession.userAgent).name match {
      case maybeEmpty if maybeEmpty == null || maybeEmpty.isEmpty => "unknown"
      case other => other
    }
    asyncIteratee(streamSession, versionOpt) { jsArr =>
      Option(jsArr.value(0)).flatMap(_.asOpt[String]).flatMap(handlers.get).map { handler =>
        val action = jsArr.value(0).as[String]
        statsd.time(s"websocket.handler.$action", ONE_IN_HUNDRED) { t =>
          val timer = accessLog.timer(WS_IN)
          val payload = jsArr.value.tail
          try {
            handler(payload)
          } finally {
            statsd.incrementOne(s"websocket.handler.$action.$agentFamily", ONE_IN_HUNDRED)
            accessLog.add(timer.done(url = action, trackingId = socketInfo.trackingId, method = "MESSAGE", query = payload.toString()))
          }
        }
      } getOrElse {
        airbrake.notify(s"WS no handler from user ${streamSession.userId} for: " + jsArr + s"(${socketInfo.kifiVersion} :: ${streamSession.userAgent})")
      }
    }.map(_ => endSession(streamSession, socketInfo))
  }

  private def updateNeeded(versionOpt: Option[KifiVersion], userId: Id[User]): Boolean = {
    versionOpt match {
      case Some(ver: KifiExtVersion) =>
        val details = kifInstallationStore.get()
        if (ver < details.gold) {
          log.info(s"User $userId is running an old extension ($ver). Upgrade incoming!")
          true
        } else if (details.killed.contains(ver)) {
          log.info(s"User $userId is running a killed extension ($ver). Upgrade incoming!")
          true
        } else {
          false
        }
      case Some(ver: KifiIPhoneVersion) =>
        false // TODO
      case Some(ver: KifiAndroidVersion) =>
        false // TODO
      case _ =>
        false
    }
  }

  private def endSession(streamSession: StreamSession, socketInfo: SocketInfo) = {
    val timer = accessLog.timer(WS_IN)
    try {
      socketInfo.channel.push(Json.arr("bye", "session"))
      socketInfo.channel.eofAndEnd()
    } catch {
      case ex: java.nio.channels.ClosedChannelException =>
    } finally {
      onDisconnect(socketInfo)
      accessLog.add(timer.done(trackingId = socketInfo.trackingId, method = "DISCONNECT", body = "Session ended"))
    }
  }

  protected def authenticatedWebSocketsContextBuilder(socketInfo: SocketInfo, request: Option[RequestHeader] = None) = {
    val contextBuilder = heimdalContextBuilder()
    contextBuilder.addExperiments(socketInfo.experiments)
    contextBuilder.addUserAgent(socketInfo.userAgent)
    request.foreach(contextBuilder.addRequestInfo)
    socketInfo.ip.foreach(contextBuilder.addRemoteAddress)
    socketInfo.kifiVersion.foreach { ver => contextBuilder += ("extensionVersion", ver.toString) }
    contextBuilder
  }
}
