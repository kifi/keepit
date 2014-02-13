package com.keepit.eliza.controllers

import com.keepit.common.strings._
import com.keepit.common.controller.ElizaServiceController
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.model.{KifiVersion, User, SocialUserInfo, ExperimentType}
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.SocialNetworkType
import com.keepit.common.controller.FortyTwoCookies.ImpersonateCookie
import com.keepit.common.time._
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.heimdal._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.crypto.SimpleDESCrypt
import com.keepit.commanders.RemoteUserExperimentCommander
import scala.util.Try

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.{Future, Promise}
import scala.util.Random

import play.api.mvc.{WebSocket,RequestHeader}
import play.api.libs.iteratee.{Enumerator,Iteratee, Concurrent}
import play.api.mvc.WebSocket.FrameFormatter
import play.api.libs.json.{Json, JsValue}
import play.modules.statsd.api.Statsd

import akka.actor.ActorSystem

import securesocial.core.{Authenticator, UserService, SecureSocial}

import org.joda.time.DateTime
import play.api.libs.json.JsArray
import com.keepit.social.SocialId
import com.keepit.common.net.UserAgent
import com.keepit.common.store.KifInstallationStore
import com.keepit.common.logging.{AccessLogTimer, AccessLog}
import com.keepit.common.logging.Access.WS_IN
import org.apache.commons.lang3.RandomStringUtils

case class StreamSession(userId: Id[User], socialUser: SocialUserInfo, experiments: Set[ExperimentType], adminUserId: Option[Id[User]], userAgent: String)

case class SocketInfo(
  channel: Concurrent.Channel[JsArray],
  connectedAt: DateTime,
  userId: Id[User],
  experiments: Set[ExperimentType],
  extVersion: Option[String],
  userAgent: String,
  var ip: Option[String],
  id: Long = Random.nextLong(),
  trackingId: String = RandomStringUtils.randomAlphanumeric(5)
)

trait AuthenticatedWebSocketsController extends ElizaServiceController {

  protected val shoebox: ShoeboxServiceClient
  protected val impersonateCookie: ImpersonateCookie
  protected val actorSystem: ActorSystem
  protected val clock: Clock
  protected val airbrake: AirbrakeNotifier
  protected val heimdal: HeimdalServiceClient
  protected val heimdalContextBuilder: HeimdalContextBuilderFactory
  protected val userExperimentCommander: RemoteUserExperimentCommander
  val kifInstallationStore: KifInstallationStore
  val accessLog: AccessLog

  protected def onConnect(socket: SocketInfo) : Unit
  protected def onDisconnect(socket: SocketInfo) : Unit
  protected def websocketHandlers(socket: SocketInfo) : Map[String, Seq[JsValue] => Unit]

  protected val crypt = new SimpleDESCrypt
  protected val ipkey = crypt.stringToKey("dontshowtheiptotheclient")

  private def asyncIteratee(streamSession: StreamSession, extVersionOpt: Option[String])(f: JsArray => Unit): Iteratee[JsArray, Unit] = {
    val extVersion = extVersionOpt.getOrElse("N/A")
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
                url = e.value.headOption.map(_.toString),
                message = Some(s"[WS] user ${streamSession.userId.id} using version $extVersion on ${streamSession.userAgent.abbreviate(30)} making call ${e.toString}")
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

  private def authenticate(implicit request: RequestHeader): Option[Future[StreamSession]] = {
    //Apologies for the nasty future nesting. Improvement suggestions appreciated.
    for (
      auth <- getAuthenticatorFromRequest();
      secSocialUser <- UserService.find(auth.identityId)
    ) yield {

      val impersonatedUserIdOpt: Option[ExternalId[User]] =
        impersonateCookie.decodeFromCookie(request.cookies.get(impersonateCookie.COOKIE_NAME))

      val socialUserFuture = shoebox.getSocialUserInfoByNetworkAndSocialId(SocialId(secSocialUser.identityId.userId), SocialNetworkType(secSocialUser.identityId.providerId)).map(_.get)

      socialUserFuture.flatMap{ socialUser =>
        val userId = socialUser.userId.get
        val experimentsFuture = userExperimentCommander.getExperimentsByUser(userId)
        experimentsFuture.flatMap{ experiments =>
          impersonatedUserIdOpt match {
            case Some(impExtUserId) if experiments.contains(ExperimentType.ADMIN) =>
              val impUserIdFuture = shoebox.getUserOpt(impExtUserId).map(_.get.id.get)
              impUserIdFuture.flatMap{ impUserId =>
                shoebox.getSocialUserInfosByUserId(impUserId).map{ impSocUserInfo =>
                  StreamSession(impUserId, impSocUserInfo.head, experiments, Some(userId), request.headers.get("User-Agent").getOrElse("NA"))
                }
              }
            case None if experiments.contains(ExperimentType.ADMIN) =>
              Promise.successful(StreamSession(userId, socialUser, experiments, Some(userId), request.headers.get("User-Agent").getOrElse("NA"))).future
            case _ =>
              Promise.successful(StreamSession(userId, socialUser, experiments, None, request.headers.get("User-Agent").getOrElse("NA"))).future
          }
        }
      }
    }
  }


  def websocket(versionOpt: Option[String], eipOpt: Option[String]) = WebSocket.async[JsArray] { implicit request =>
    authenticate(request) match {
      case Some(streamSessionFuture) =>  streamSessionFuture.map { streamSession =>
        val connectTimer = accessLog.timer(WS_IN)
        implicit val (enumerator, channel) = Concurrent.broadcast[JsArray]

        val ipOpt : Option[String] = eipOpt.flatMap{ eip =>
          crypt.decrypt(ipkey, eip).toOption
        }
        val socketInfo = SocketInfo(channel, clock.now, streamSession.userId, streamSession.experiments, versionOpt, streamSession.userAgent, ipOpt)

        var startMessages = Seq[JsArray](Json.arr("hi"))

        if (needsToUpdate(streamSession, versionOpt)) {
          val details = kifInstallationStore.get()
          startMessages = startMessages :+ Json.arr("version", details.gold.toString)
        }

        onConnect(socketInfo)

        reportConnect(streamSession, socketInfo, request, connectTimer)

        (iteratee(streamSession, versionOpt, socketInfo, channel), Enumerator(startMessages: _*) >>> enumerator)
      }
      case None => Future {
        Statsd.increment(s"websocket.anonymous")
        log.info("Disconnecting anonymous user")
        (Iteratee.ignore, Enumerator(Json.arr("denied")) >>> Enumerator.eof)
      }
    }
  }

  private def reportConnect(streamSession: StreamSession, socketInfo: SocketInfo, request: RequestHeader, connectTimer: AccessLogTimer): Unit = {
    log.info(s"New WS connection from user $streamSession")
    val tStart = currentDateTime
    //Analytics
    SafeFuture {
      val context = authenticatedWebSocketsContextBuilder(socketInfo, Some(request)).build
      heimdal.trackEvent(UserEvent(socketInfo.userId, context, UserEventTypes.CONNECTED, tStart))
      heimdal.setUserProperties(socketInfo.userId, "lastConnected" -> ContextDate(tStart))
    }
    accessLog.add(connectTimer.done(trackingId = socketInfo.trackingId, method = "CONNECT"))
  }

  private def iteratee(streamSession: StreamSession, versionOpt: Option[String], socketInfo: SocketInfo, channel: Concurrent.Channel[JsArray]) = {
    val handlers = websocketHandlers(socketInfo)
    asyncIteratee(streamSession, versionOpt) { jsArr =>
      Option(jsArr.value(0)).flatMap(_.asOpt[String]).flatMap(handlers.get).map { handler =>
        val action = jsArr.value(0).as[String]
        Statsd.increment(s"websocket.handler.$action")
        Statsd.time(s"websocket.handler.$action") {
          val timer = accessLog.timer(WS_IN)
          val payload = jsArr.value.tail
          try {
            handler(payload)
          } finally {
            accessLog.add(timer.done(url = action, trackingId = socketInfo.trackingId, method = "MESSAGE", query = payload.toString()))
          }
        }
      } getOrElse {
        log.warn(s"WS no handler from user ${streamSession.userId} for: " + jsArr)
      }
    }.map(_ => endSession("Session ended", streamSession, socketInfo, channel))
  }

  private def needsToUpdate(streamSession: StreamSession, versionOpt: Option[String]) = {
    // We only support force updates to Chrome. Other platforms (mobile) can hook in here as well.
    if (UserAgent.fromString(streamSession.userAgent).name == "Chrome") {
      versionOpt.flatMap(v => Try(KifiVersion(v)).toOption) match {
        case Some(ver) =>
          val details = kifInstallationStore.get()
          val lessThanGold = ver < details.gold
          val runningKilledVersion = details.killed.contains(ver)
          if (lessThanGold) {
            log.info(s"${streamSession.userId} is running an old extension (${ver.toString}). Upgrade incoming!")
          }
          if (runningKilledVersion) {
            log.info(s"${streamSession.userId} is running a killed extension (${ver.toString}). Upgrade incoming!")
          }
          lessThanGold || runningKilledVersion
        case None => false
      }
    } else {
      false
    }
  }

  private def endSession(reason: String, streamSession: StreamSession, socketInfo: SocketInfo, channel: Concurrent.Channel[JsArray]) = {
    log.info(s"Closing socket of userId ${streamSession.userId} because: $reason")
    val timer = accessLog.timer(WS_IN)
    channel.push(Json.arr("goodbye", reason))
    channel.eofAndEnd()
    onDisconnect(socketInfo)
    accessLog.add(timer.done(trackingId = socketInfo.trackingId, method = "DISCONNECT"))
  }

  protected def authenticatedWebSocketsContextBuilder(socketInfo: SocketInfo, request: Option[RequestHeader] = None) = {
    val contextBuilder = heimdalContextBuilder()
    contextBuilder.addExperiments(socketInfo.experiments)
    contextBuilder.addUserAgent(socketInfo.userAgent)
    request.foreach(contextBuilder.addRequestInfo)
    socketInfo.ip.foreach(contextBuilder.addRemoteAddress)
    socketInfo.extVersion.foreach{ version => contextBuilder += ("extensionVersion", version) }
    contextBuilder
  }
}
