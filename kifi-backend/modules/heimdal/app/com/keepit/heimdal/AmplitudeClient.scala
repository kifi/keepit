package com.keepit.heimdal

import com.google.common.base.CaseFormat
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.model.User
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.libs.ws.WS

import scala.concurrent.Future

object AmplitudeClient {
  val killedProperties = Set("agent", "agentVersion", "client", "clientBuild", "clientVersion", "daysSinceLibraryCreated",
    "daysSinceUserJoined", "device", "experiments", "extensionVersion", "kcid_6", "kcid_7", "kcid_8",
    "kcid_9", "kcid_10", "kcid_11", "os", "osVersion", "remoteAddress", "serviceInstance", "serviceZone", "userCreatedAt",
    "userId", "userSegment")
}

trait AmplitudeClient {
  def track[E <: HeimdalEvent](event: E)(implicit companion: HeimdalEventCompanion[E]): Future[Unit]
}

class AmplitudeClientImpl(apiKey: String) extends AmplitudeClient {

  val amplitudeApiEndpoint = "https://api.amplitude.com/httpapi"

  def track[E <: HeimdalEvent](event: E)(implicit companion: HeimdalEventCompanion[E]): Future[Unit] = {
    new SafeFuture({
      val payload = buildEventPayload(event)
      sendData(payload) map (_ => ())
    })
  }

  private def buildEventPayload[E <: HeimdalEvent](event: E)(implicit companion: HeimdalEventCompanion[E]): JsObject = {
    val eventName = s"${companion.typeCode}_${event.eventType.name}"

    val heimdalContextBuilder = new HeimdalContextBuilder()
    heimdalContextBuilder.data ++= event.context.data
    if (!heimdalContextBuilder.data.contains("ip")) {
      heimdalContextBuilder.data += ("ip" -> ContextStringData(getIpAddress(event) getOrElse "0"))
    }
    heimdalContextBuilder += ("distinct_id", getDistinctId(event))

    val (userProperties, eventProperties) = separateUserAndEventProperties(heimdalContextBuilder.build)

    Json.obj(
      "user_id" -> getUserId(event),
      "device_id" -> getDistinctId(event),
      "event_type" -> eventName,
      "time" -> event.time.getMillis / 1000,
      "event_properties" -> Json.toJson(eventProperties),
      "user_properties" -> Json.toJson(userProperties),
      "app_version" -> eventProperties.get("client_version"),
      "platform" -> eventProperties.get("client"),
      "os_name" -> eventProperties.get("os"),
      "os_version" -> eventProperties.get("os_version"),
      "device_type" -> eventProperties.get("device"),
      "language" -> event.context.get[String]("language"),
      "ip" -> getIpAddress(event)
    )
  }

  private def separateUserAndEventProperties(context: HeimdalContext) = {
    val (userProps, eventProps) = context.data.
      filterKeys(key => !AmplitudeClient.killedProperties.contains(key)).
      partition {
        // return true if the property is a "user property" (not related to the particular event)
        case (key, _) =>
          // ugly - it'd be nicer if a HeimdalContext was aware of which properties are user properties vs event properties,
          // but that'll require a decent sized refactor which isn't necessary to get this the initial integration out the door
          (key.startsWith("user") && key != "userAgent") ||
            key.startsWith("exp_") ||
            key.startsWith("kcid") ||
            Seq("gender").contains(key)
      }

    (xformToSnakeCase(userProps), xformToSnakeCase(eventProps))
  }

  private def getUserId(event: HeimdalEvent): Option[String] = event match {
    case userEvent: UserEvent => Some(getDistinctId(userEvent.userId))
    case _ => None
  }

  private def getIpAddress(event: HeimdalEvent): Option[String] =
    event.context.get[String]("ip") orElse event.context.get[String]("remoteAddress")

  private def getDistinctId(id: Id[User]): String = s"${UserEvent.typeCode}_${id.toString}"

  private def getDistinctId[E <: HeimdalEvent](event: E)(implicit companion: HeimdalEventCompanion[E]): String = event match {
    case userEvent: UserEvent => getDistinctId(userEvent.userId)
    case nonUserEvent: NonUserEvent => nonUserEvent.identifier
    case visitorEvent: VisitorEvent =>
      visitorEvent.context.get[String]("distinct_id") orElse
        getIpAddress(visitorEvent) getOrElse
        VisitorEvent.typeCode
    case systemEvent: SystemEvent => SystemEvent.typeCode
    case anonymousEvent: AnonymousEvent => AnonymousEvent.typeCode
  }

  private def sendData(eventData: JsObject): Future[Unit] = {
    val eventJson = Json.stringify(eventData)
    val request = WS.url(amplitudeApiEndpoint).withQueryString("event" -> eventJson, "api_key" -> apiKey)

    request.get() map {
      case resp if resp.status != 200 =>
        throw new RuntimeException(s"Amplitude endpoint $amplitudeApiEndpoint refused event: " +
          s"status=${resp.status} message=${resp.body} payload=$eventJson")
      case _ => ()
    }
  }

  private def xformToSnakeCase(data: Map[String, ContextData]): Map[String, ContextData] = {
    data.foldLeft(Map.empty[String, ContextData]) {
      case (acc, (key, context)) => acc.updated(camelCaseToUnderscore(key), context)
    }
  }

  private def camelCaseToUnderscore(key: String): String = {
    CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, key)
  }

}
