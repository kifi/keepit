package com.keepit.heimdal

import com.google.common.base.CaseFormat
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.model.{ UserExperimentType, User }
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

  val killedEvents = Set("user_old_slider_sliderShown", "user_expanded_keeper", "user_used_kifi", "user_reco_action",
    "anonymous_kept", "user_logged_in", "visitor_expanded_keeper", "visitor_reco_action", "visitor_viewed_notification",
    "visitor_clicked_notification", "anonymous_visitor_viewed_page", "anonymous_messaged")

  private val experimentsToTrack: Set[String] = UserExperimentType._TRACK_FOR_ANALYTICS.map(_.value)
  val trackedPropertyFilters: Seq[(String) => Boolean] = Seq(
    (field) => !killedProperties.contains(field),
    // kill exp_ properties except those that exist in UserExperimentType._TRACK_FOR_ANALYTICS
    (field) => !field.startsWith("exp_") || experimentsToTrack.contains(field.substring(4))
  )
}

trait AmplitudeClient {
  def track[E <: HeimdalEvent](event: E)(implicit companion: HeimdalEventCompanion[E]): Future[Unit]
}

class AmplitudeClientImpl(apiKey: String) extends AmplitudeClient {

  val amplitudeApiEndpoint = "https://api.amplitude.com/httpapi"

  def track[E <: HeimdalEvent](event: E)(implicit companion: HeimdalEventCompanion[E]): Future[Unit] = {
    val eventBuilder = new AmplitudeEventBuilder(event)

    if (AmplitudeClient.killedEvents.contains(eventBuilder.getEventType())) Future.successful(Unit)
    else new SafeFuture({
      val payload = eventBuilder.buildEventPayload()
      sendData(payload) map (_ => ())
    })
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
}

class AmplitudeEventBuilder[E <: HeimdalEvent](val event: E)(implicit companion: HeimdalEventCompanion[E]) {
  import AmplitudeClient._

  val heimdalContext = {
    val heimdalContextBuilder = new HeimdalContextBuilder()
    heimdalContextBuilder.data ++= event.context.data
    event.context
  }

  def getEventType(): String = {
    s"${companion.typeCode}_${event.eventType.name}"
  }

  def buildEventPayload(): JsObject = {
    val (userProperties, eventProperties) = getUserAndEventProperties()

    Json.obj(
      "user_id" -> getUserId(),
      "device_id" -> getDistinctId(),
      "event_type" -> getEventType(),
      "time" -> event.time.getMillis / 1000,
      "event_properties" -> Json.toJson(eventProperties),
      "user_properties" -> Json.toJson(userProperties),
      "app_version" -> heimdalContext.get[String]("clientVersion"),
      "platform" -> heimdalContext.get[String]("client"),
      "os_name" -> heimdalContext.get[String]("os"),
      "os_version" -> heimdalContext.get[String]("osVersion"),
      "device_type" -> heimdalContext.get[String]("device"),
      "language" -> heimdalContext.get[String]("language"),
      "ip" -> getIpAddress()
    )
  }

  private def getUserAndEventProperties() = {
    val (userProps, eventProps) = heimdalContext.data.
      filterKeys(key => trackedPropertyFilters.forall(fn => fn(key))).
      partition {
        // return true if the property is a "user property" (not related to the particular event)
        case (key, _) =>
          // ugly - it'd be nicer if a HeimdalContext was aware of which properties are user properties vs event properties,
          // but that'll require a decent sized refactor which isn't necessary to get this the initial integration out the door
          (key.startsWith("user") && key != "userAgent") ||
            key.startsWith("exp_") ||
            key.startsWith("kcid") ||
            key == "gender"
      }

    (xformToSnakeCase(userProps), xformToSnakeCase(eventProps))
  }

  private def getUserId(): Option[String] = event match {
    case userEvent: UserEvent => Some(getDistinctId(userEvent.userId))
    case _ => None
  }

  private def getIpAddress(): Option[String] =
    heimdalContext.get[String]("ip") orElse heimdalContext.get[String]("remoteAddress")

  private def getDistinctId(id: Id[User]): String = s"${UserEvent.typeCode}_${id.toString}"

  private def getDistinctId(): String = event match {
    case userEvent: UserEvent => getDistinctId(userEvent.userId)
    case nonUserEvent: NonUserEvent => nonUserEvent.identifier
    case visitorEvent: VisitorEvent =>
      heimdalContext.get[String]("distinct_id") orElse
        getIpAddress() getOrElse
        VisitorEvent.typeCode
    case systemEvent: SystemEvent => SystemEvent.typeCode
    case anonymousEvent: AnonymousEvent => AnonymousEvent.typeCode
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
