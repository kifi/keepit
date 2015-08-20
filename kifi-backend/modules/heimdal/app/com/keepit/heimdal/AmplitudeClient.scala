package com.keepit.heimdal

import com.google.common.base.CaseFormat
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.net.WebService
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
  def track[E <: HeimdalEvent](event: E)(implicit companion: HeimdalEventCompanion[E]): Future[AmplitudeEventResult]
  def setUserProperties(userId: Id[User], context: HeimdalContext): Future[AmplitudeEventResult]
  def alias(userId: Id[User], externalId: ExternalId[User]): Future[AmplitudeEventResult]
}

trait AmplitudeEventResult
case class AmplitudeEventSkipped(eventType: String) extends AmplitudeEventResult
case class AmplitudeApiError(message: String) extends AmplitudeEventResult
case class AmplitudeEventSent(eventData: JsObject) extends AmplitudeEventResult

class AmplitudeClientImpl(apiKey: String, primaryOrgProvider: PrimaryOrgProvider, ws: WebService) extends AmplitudeClient {
  val eventApiEndpoint = "https://api.amplitude.com/httpapi"
  val identityApiEndpoint = "https://api.amplitude.com/identify"

  def track[E <: HeimdalEvent](event: E)(implicit companion: HeimdalEventCompanion[E]): Future[AmplitudeEventResult] = {
    val eventBuilder = new AmplitudeEventBuilder(event, primaryOrgProvider)

    if (AmplitudeClient.skipEventFilters.exists(fn => fn(eventBuilder))) Future.successful(AmplitudeEventSkipped(eventBuilder.getEventType()))
    else new SafeFuture({
      eventBuilder.build() flatMap { eventData =>
        val eventJson = Json.stringify(eventData)
        val request = ws.url(eventApiEndpoint).withQueryString("event" -> eventJson, "api_key" -> apiKey)

        request.get() map {
          case resp if resp.status != 200 =>
            AmplitudeApiError(s"Amplitude endpoint $eventApiEndpoint refused event: " +
              s"status=${resp.status} message=${resp.body} payload=$eventJson")
          case _ => AmplitudeEventSent(eventData)
        }
      }
    }, Some(s"AmplitudeClientImpl.track(event=${eventBuilder.getEventType()})"))
  }

  def setUserProperties(userId: Id[User], context: HeimdalContext) = {
    val identityBuilder = new AmplitudeIdentityBuilder(userId, context, primaryOrgProvider)
    identityBuilder.build(userId) flatMap { eventData =>
      val identityJson = Json.stringify(eventData)
      val request = ws.url(identityApiEndpoint).withQueryString("identification" -> identityJson, "api_key" -> apiKey)

      request.get() map {
        case resp if resp.status != 200 =>
          AmplitudeApiError(s"Amplitude endpoint $identityApiEndpoint refused event: " +
            s"status=${resp.status} message=${resp.body} payload=$identityJson")
        case _ => AmplitudeEventSent(eventData)
      }
    }
  }

  def alias(userId: Id[User], externalId: ExternalId[User]): Future[AmplitudeEventResult] = {
    val context = new HeimdalContext(Map("distinct_id" -> ContextStringData(externalId.id)))
    setUserProperties(userId, context)
  }
}

// translates specific properties (originally created for MixPanel) into for amplitude
case class AmplitudeSpecificProperties(heimdalContext: HeimdalContext) {
  val distinctId = heimdalContext.get[String]("distinct_id")
  val appVersion = heimdalContext.get[String]("clientVersion")
  val platform = heimdalContext.get[String]("client")
  val osName = heimdalContext.get[String]("os")
  val osVersion = heimdalContext.get[String]("osVersion")
  val deviceType = heimdalContext.get[String]("device")
  val language = heimdalContext.get[String]("language")
}

trait AmplitudeRequestBuilder {
  import AmplitudeClient._

  def heimdalContext: HeimdalContext
  def primaryOrgProvider: PrimaryOrgProvider
  def userIdOpt: Option[Id[User]]
  def specificProperties: AmplitudeSpecificProperties

  def getUserAndEventProperties(): Future[(Map[String, ContextData], Map[String, ContextData])] = {
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

    userIdOpt.map { userId =>
      primaryOrgProvider.getPrimaryOrg(userId).flatMap {
        case Some(orgId) =>
          val orgValuesFut = primaryOrgProvider.getPrimaryOrgValues(orgId)
          orgValuesFut.map { values =>
            val orgProps = Map(
              "orgId" -> ContextStringData(orgId.toString),
              "libraryCount" -> ContextDoubleData(values.libraryCount.toDouble),
              "keepCount" -> ContextDoubleData(values.keepCount.toDouble),
              "inviteCount" -> ContextDoubleData(values.inviteCount.toDouble),
              "collabLibCount" -> ContextDoubleData(values.collabLibCount.toDouble),
              "messageCount" -> ContextDoubleData(values.messageCount.map(_.toDouble).getOrElse(-1)),
              "popularKeeper" -> ContextStringData(values.popularKeeper.map(_.toString).getOrElse(""))
              )
            (xformToSnakeCase(userProps ++ orgProps), xformToSnakeCase(eventProps ++ orgProps))
          }
        case None =>
          Future.successful(xformToSnakeCase(userProps), xformToSnakeCase(eventProps))
      }
    }.getOrElse(Future.successful((xformToSnakeCase(userProps), xformToSnakeCase(eventProps))))
  }

  def getUserId(): Option[String] = userIdOpt.map { userId => getDistinctId(userId) }

  protected def getDistinctId(): String

  protected def getDistinctId(id: Id[User]): String = s"${UserEvent.typeCode}_${id.toString}"

  private def xformToSnakeCase(data: Map[String, ContextData]): Map[String, ContextData] = {
    data.foldLeft(Map.empty[String, ContextData]) {
      case (acc, (key, context)) => acc.updated(camelCaseToUnderscore(key), context)
    }
  }

  private def camelCaseToUnderscore(key: String): String = {
    CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, key)
  }
}

class AmplitudeIdentityBuilder(val userId: Id[User], val heimdalContext: HeimdalContext, val primaryOrgProvider: PrimaryOrgProvider) extends AmplitudeRequestBuilder {
  val userIdOpt = Some(userId)

  val specificProperties = AmplitudeSpecificProperties(heimdalContext)

  def build(userId: Id[User]): Future[JsObject] = {
    getUserAndEventProperties() map {
      case (userProperties, _) =>
        Json.obj(
          "user_id" -> getUserId(),
          "device_id" -> getDistinctId(),
          "user_properties" -> Json.toJson(userProperties),
          "app_version" -> specificProperties.appVersion,
          "platform" -> specificProperties.platform,
          "os_name" -> specificProperties.osName,
          "os_version" -> specificProperties.osVersion,
          "device_type" -> specificProperties.deviceType,
          "language" -> specificProperties.language
        )
    }
  }

  protected def getDistinctId(): String = specificProperties.distinctId getOrElse getDistinctId(userId)
}

class AmplitudeEventBuilder[E <: HeimdalEvent](val event: E, val primaryOrgProvider: PrimaryOrgProvider)(implicit companion: HeimdalEventCompanion[E]) extends AmplitudeRequestBuilder {
  val heimdalContext = {
    val heimdalContextBuilder = new HeimdalContextBuilder()
    heimdalContextBuilder.data ++= event.context.data
    event.context
  }

  val specificProperties = AmplitudeSpecificProperties(heimdalContext)

  val userIdOpt = event match {
    case userEvent: UserEvent => Some(userEvent.userId)
    case _ => None
  }

  def getEventType(): String = {
    s"${companion.typeCode}_${event.eventType.name}"
  }

  def build(): Future[JsObject] = {
    getUserAndEventProperties() map {
      case (userProperties, eventProperties) =>
        Json.obj(
          "user_id" -> getUserId(),
          "device_id" -> getDistinctId(),
          "event_type" -> getEventType(),
          "time" -> event.time.getMillis / 1000,
          "event_properties" -> Json.toJson(eventProperties),
          "user_properties" -> Json.toJson(userProperties),
          "app_version" -> specificProperties.appVersion,
          "platform" -> specificProperties.platform,
          "os_name" -> specificProperties.osName,
          "os_version" -> specificProperties.osVersion,
          "device_type" -> specificProperties.deviceType,
          "language" -> specificProperties.language,
          "ip" -> getIpAddress()
        )
    }
  }

  private def getIpAddress(): Option[String] =
    heimdalContext.get[String]("ip") orElse heimdalContext.get[String]("remoteAddress")

  protected def getDistinctId(): String = event match {
    case userEvent: UserEvent => getDistinctId(userEvent.userId)
    case nonUserEvent: NonUserEvent => nonUserEvent.identifier
    case visitorEvent: VisitorEvent =>
      specificProperties.distinctId orElse
        getIpAddress() getOrElse
        VisitorEvent.typeCode
    case systemEvent: SystemEvent => SystemEvent.typeCode
    case anonymousEvent: AnonymousEvent => AnonymousEvent.typeCode
  }

}
