package com.keepit.heimdal

import com.google.common.base.CaseFormat
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.model.{ User, UserExperimentType }
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

  private val killedEvents = Set("user_old_slider_sliderShown", "user_expanded_keeper", "user_used_kifi", "user_reco_action",
    "user_logged_in", "visitor_expanded_keeper", "visitor_reco_action", "visitor_viewed_notification",
    "visitor_clicked_notification")

  // do not record any of the events that that return true from any of these functions
  val skipEventFilters: Seq[AmplitudeEventBuilder[_] => Boolean] = Seq(
    (eb: AmplitudeEventBuilder[_]) => eb.getEventType().startsWith("anonymous_"),
    (eb: AmplitudeEventBuilder[_]) => eb.heimdalContext.get[String]("userAgent").exists(_.startsWith("Pingdom")),
    (eb: AmplitudeEventBuilder[_]) => AmplitudeClient.killedEvents.contains(eb.getEventType())
  )

  private val experimentsToTrack: Set[String] = UserExperimentType._TRACK_FOR_ANALYTICS.map(_.value)
  val trackedPropertyFilters: Seq[(String) => Boolean] = Seq(
    (field) => !killedProperties.contains(field),
    // kill exp_ properties except those that exist in UserExperimentType._TRACK_FOR_ANALYTICS
    (field) => !field.startsWith("exp_") || experimentsToTrack.contains(field.substring(4))
  )
}

trait AmplitudeClient {
  def track[E <: HeimdalEvent](event: E)(implicit companion: HeimdalEventCompanion[E]): Future[Boolean]
}

class AmplitudeClientImpl(primaryOrgProvider: PrimaryOrgProvider, transport: AmplitudeTransport) extends AmplitudeClient {
  def track[E <: HeimdalEvent](event: E)(implicit companion: HeimdalEventCompanion[E]): Future[Boolean] = {
    val eventBuilder = new AmplitudeEventBuilder(event, primaryOrgProvider)

    if (AmplitudeClient.skipEventFilters.exists(fn => fn(eventBuilder))) Future.successful(false)
    else new SafeFuture({
      eventBuilder.buildEventPayload() flatMap { payload =>
        transport.deliver(payload) map (_ => true)
      }
    })
  }
}

class AmplitudeEventBuilder[E <: HeimdalEvent](val event: E, primaryOrgProvider: PrimaryOrgProvider)(implicit companion: HeimdalEventCompanion[E]) {
  import AmplitudeClient._

  val heimdalContext = {
    val heimdalContextBuilder = new HeimdalContextBuilder()
    heimdalContextBuilder.data ++= event.context.data
    event.context
  }

  def getEventType(): String = {
    s"${companion.typeCode}_${event.eventType.name}"
  }

  def buildEventPayload(): Future[JsObject] = {
    getUserAndEventProperties() map {
      case (userProperties, eventProperties) =>
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
  }

  private def getUserAndEventProperties(): Future[(Map[String, ContextData], Map[String, ContextData])] = {
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

    event match {
      case userEvent: UserEvent =>
        primaryOrgProvider.getPrimaryOrg(userEvent.userId) map { orgIdOpt =>
          orgIdOpt match {
            case Some(orgId) =>
              val orgProps = "orgId" -> ContextStringData(orgId.toString)
              (xformToSnakeCase(userProps + orgProps), xformToSnakeCase(eventProps + orgProps))
            case None =>
              (xformToSnakeCase(userProps), xformToSnakeCase(eventProps))
          }
        }
      case _ =>
        Future.successful(xformToSnakeCase(userProps) -> xformToSnakeCase(eventProps))
    }
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

trait AmplitudeTransport {
  def deliver(eventData: JsObject): Future[JsObject]
}

class AmplitudeTransportImpl(apiKey: String) extends AmplitudeTransport {

  val amplitudeApiEndpoint = "https://api.amplitude.com/httpapi"

  def deliver(eventData: JsObject): Future[JsObject] = {
    val eventJson = Json.stringify(eventData)
    val request = WS.url(amplitudeApiEndpoint).withQueryString("event" -> eventJson, "api_key" -> apiKey)

    request.get() map {
      case resp if resp.status != 200 =>
        throw new RuntimeException(s"Amplitude endpoint $amplitudeApiEndpoint refused event: " +
          s"status=${resp.status} message=${resp.body} payload=$eventJson")
      case _ => eventData
    }
  }
}
