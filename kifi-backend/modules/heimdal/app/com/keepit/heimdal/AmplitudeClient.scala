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
  val killedProperties = Set("client", "clientBuild", "clientVersion",
    "device", "experiments", "extensionVersion", "kcid_6", "kcid_7", "kcid_8",
    "kcid_9", "kcid_10", "kcid_11", "os", "osVersion", "remoteAddress", "serviceInstance", "serviceZone",
    "userId", "userSegment")

  private val killedEvents = Set("user_old_slider_sliderShown", "user_expanded_keeper", "user_used_kifi", "user_reco_action",
    "user_logged_in", "visitor_expanded_keeper", "visitor_reco_action", "visitor_viewed_notification",
    "visitor_clicked_notification")

  // do not record any of the events that that return true from any of these functions
  val skipEventFilters: Seq[AmplitudeEventBuilder[_] => Boolean] = Seq(
    (eb: AmplitudeEventBuilder[_]) => eb.eventType.startsWith("anonymous_"),
    (eb: AmplitudeEventBuilder[_]) => eb.heimdalContext.get[String]("userAgent").exists(_.startsWith("Pingdom")),
    (eb: AmplitudeEventBuilder[_]) => {
      // drop all _viewed_page events where "type" starts with "/", with a few exceptions
      eb.eventType.endsWith("_viewed_page") && eb.heimdalContext.get[String]("type").exists { v =>
        v.head == '/' && !Set("/settings", "/tags/manage").contains(v) && !v.startsWith("/?m=")
      }
    },
    (eb: AmplitudeEventBuilder[_]) => AmplitudeClient.killedEvents.contains(eb.eventType)
  )

  private val experimentsToTrack: Set[String] = UserExperimentType._TRACK_FOR_ANALYTICS.map(_.value)
  val trackedPropertyFilters: Seq[(String) => Boolean] = Seq(
    (field) => !killedProperties.contains(field),
    // kill exp_ properties except those that exist in UserExperimentType._TRACK_FOR_ANALYTICS
    (field) => !field.startsWith("exp_") || experimentsToTrack.contains(field.substring(4))
  )

  val propertyRenames = Map(
    "kifiInstallationId" -> "installation_id",
    "userCreatedAt" -> "created_at",
    "$email" -> "email"
  )

  // classifies properties with these names as "user properties"
  val userPropertyNames = Set(
    "firstName", "lastName", "$email", "gender",
    "keeps", "kifiConnections", "privateKeeps", "publicKeeps", "socialConnections", "tags", "daysSinceLibraryCreated",
    "daysSinceUserJoined", "orgKeepCount", "orgMessageCount", "orgInviteCount", "orgLibrariesCreated", "orgLibrariesCollaborating",
    "overallKeepViews", "orgId")

  // rename events with these names
  val simpleEventRenames: Map[String, String] = Map(
    "user_modified_library" -> "user_created_library",
    "user_changed_setting" -> "user_changed_settings",
    "user_viewed_pane" -> "user_viewed_page",
    "visitor_viewed_pane" -> "visitor_viewed_page"
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

class AmplitudeClientImpl(apiKey: String, ws: WebService) extends AmplitudeClient {
  val eventApiEndpoint = "https://api.amplitude.com/httpapi"
  val identityApiEndpoint = "https://api.amplitude.com/identify"

  def track[E <: HeimdalEvent](event: E)(implicit companion: HeimdalEventCompanion[E]): Future[AmplitudeEventResult] = {
    val eventBuilder = new AmplitudeEventBuilder(event)

    if (AmplitudeClient.skipEventFilters.exists(fn => fn(eventBuilder))) Future.successful(AmplitudeEventSkipped(eventBuilder.eventType))
    else new SafeFuture({
      val eventData = eventBuilder.build()
      val eventJson = Json.stringify(eventData)
      val request = ws.url(eventApiEndpoint).withQueryString("event" -> eventJson, "api_key" -> apiKey)

      request.get() map {
        case resp if resp.status != 200 =>
          AmplitudeApiError(s"Amplitude endpoint $eventApiEndpoint refused event: " +
            s"status=${resp.status} message=${resp.body} payload=$eventJson")
        case _ => AmplitudeEventSent(eventData)
      }
    }, Some(s"AmplitudeClientImpl.track(event=${eventBuilder.eventType})"))
  }

  def setUserProperties(userId: Id[User], context: HeimdalContext) = {
    val identityBuilder = new AmplitudeIdentityBuilder(userId, context)
    val eventData = identityBuilder.build(userId)
    val identityJson = Json.stringify(eventData)
    val request = ws.url(identityApiEndpoint).withQueryString("identification" -> identityJson, "api_key" -> apiKey)

    request.get() map {
      case resp if resp.status != 200 =>
        AmplitudeApiError(s"Amplitude endpoint $identityApiEndpoint refused event: " +
          s"status=${resp.status} message=${resp.body} payload=$identityJson")
      case _ => AmplitudeEventSent(eventData)
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
  def userIdOpt: Option[Id[User]]
  def specificProperties: AmplitudeSpecificProperties

  def getUserAndEventProperties(): (Map[String, ContextData], Map[String, ContextData]) = {
    val (origUserProps, origEventProps) = heimdalContext.data.
      filterKeys(key => trackedPropertyFilters.forall(fn => fn(key))).
      partition {
        // return true if the property is a "user property" (not related to the particular event)
        case (key, _) =>
          // ugly - it'd be nicer if a HeimdalContext was aware of which properties are user properties vs event properties,
          // but that'll require a decent sized refactor which isn't necessary to get this the initial integration out the door
          (key.startsWith("user") && key != "userAgent") ||
            key.startsWith("exp_") ||
            key.startsWith("kcid") ||
            userPropertyNames.contains(key)
      }

    val (userProps, eventProps) = (renameProperties(origUserProps), renameProperties(origEventProps))

    (xformToSnakeCase(userProps), xformToSnakeCase(eventProps))
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

  private def renameProperties(props: Map[String, ContextData]): Map[String, ContextData] = {
    propertyRenames.foldLeft(props) {
      case (props, (from, to)) => props.get(from).map { value =>
        (props - from).updated(to, value)
      }.getOrElse(props)
    }
  }
}

class AmplitudeIdentityBuilder(val userId: Id[User], val heimdalContext: HeimdalContext) extends AmplitudeRequestBuilder {
  val userIdOpt = Some(userId)

  val specificProperties = AmplitudeSpecificProperties(heimdalContext)

  def build(userId: Id[User]): JsObject = {
    getUserAndEventProperties() match {
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

class AmplitudeEventBuilder[E <: HeimdalEvent](val event: E)(implicit companion: HeimdalEventCompanion[E]) extends AmplitudeRequestBuilder {
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

  val origEventName = s"${companion.typeCode}_${event.eventType.name}"

  lazy val eventType: String = {
    AmplitudeClient.simpleEventRenames.getOrElse(origEventName, {
      // specific rules for renaming event types
      // TODO(josh) after amplitude is in prod, change these events at their source
      if (origEventName == "user_joined" && heimdalContext.get[String]("action").contains("registered")) "user_registered"
      else if (origEventName == "user_joined" && heimdalContext.get[String]("action").contains("installed")) "user_installed"
      else origEventName
    })
  }


  override def getUserAndEventProperties(): (Map[String, ContextData], Map[String, ContextData]) = {
    val (userProperties, eventProperties) = super.getUserAndEventProperties()
    (userProperties, augmentEventProperties(eventProperties))
  }

  def build(): JsObject = {
    getUserAndEventProperties() match {
      case (userProperties, eventProperties) =>
        Json.obj(
          "user_id" -> getUserId(),
          "device_id" -> getDistinctId(),
          "event_type" -> eventType,
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

  val userViewedPagePaneTypes = Set("libraryChooser", "keepDetails", "messages:all", "composeMessage", "createLibrary",
    "chat", "messages:unread", "messages:page", "messages:sent")

  val userViewedPageModalTypes = Set("importBrowserBookmarks", "import3rdPartyBookmarks", "addAKeep", "getExtension", "getMobile")

  val visitorViewedPageModalTypes = Set("libraryLandingPopup", "signupLibrary", "signup2Library", "forgotPassword", "resetPassword")

  private def augmentEventProperties(eventProperties: Map[String, ContextData]): Map[String, ContextData] = {
    val builder = { val b = new HeimdalContextBuilder; b ++= eventProperties; b }

    lazy val typeProperty = eventProperties.get("type").map {
      case data: ContextStringData => data.value
      case data => data.toString
    }

    def isUserViewedPagePaneType = typeProperty.exists(v => userViewedPagePaneTypes.contains(v) || v.startsWith("guide"))
    def isUserViewedPageModalType = typeProperty.exists(v => userViewedPageModalTypes.contains(v))

    if (origEventName == "user_viewed_pane" || origEventName == "user_viewed_page") {
      builder += ("page_type", {
        if (isUserViewedPagePaneType) "pane"
        else if (isUserViewedPageModalType) "modal"
        else "page"
      })

      // spec would like these particular type property values to be renamed
      typeProperty foreach {
        case "/settings" => builder += ("type", "settings")
        case "/tags/manage" => builder += ("type", "manageTags")
        case _ =>
      }
    }

    def isVisitorViewedPagePaneType = typeProperty.contains("login")
    def isVisitorViewedPageModalType = typeProperty.exists(visitorViewedPageModalTypes.contains)

    if (origEventName == "visitor_viewed_pane" || origEventName == "visitor_viewed_page") {
      builder += ("page_type", {
        if (isVisitorViewedPagePaneType) "pane"
        else if (isVisitorViewedPageModalType) "modal"
        else "page"
      })
    }

    builder.build.data
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
