package com.keepit.slack

import com.keepit.common.cache._
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.model.{Library, Organization}
import com.keepit.slack.models._
import com.kifi.macros.json
import play.api.libs.json._

import scala.concurrent.duration.Duration


sealed trait SlackAuthenticatedAction { self =>
  type A >: self.type <: SlackAuthenticatedAction
  def instance: A = self
  def helper: SlackAuthenticatedActionHelper[A]
  def getMissingScopes(existingScopes: Set[SlackAuthScope]): Set[SlackAuthScope] = SlackAuthenticatedActionHelper.getMissingScopes(this, existingScopes)
}

object Signup extends SlackAuthenticatedActionHelper[Signup]("signup")
case class Signup() extends SlackAuthenticatedAction {
  type A = Signup
  def helper = Signup
}

object Login extends SlackAuthenticatedActionHelper[Login]("login")
case class Login() extends SlackAuthenticatedAction {
  type A = Login
  def helper = Login
}

object SetupLibraryIntegrations extends SlackAuthenticatedActionHelper[SetupLibraryIntegrations]("setup_library_integrations")
@json case class SetupLibraryIntegrations(libraryId: Id[Library], incomingWebhookId: Option[Long]) extends SlackAuthenticatedAction { // Long actually Id[SlackIncomingWebhookInfo]
  type A = SetupLibraryIntegrations
  def helper = SetupLibraryIntegrations
}

object TurnLibraryPush extends SlackAuthenticatedActionHelper[TurnLibraryPush]("turn_library_push")
@json case class TurnLibraryPush(integrationId: Long, isBroken: Boolean, turnOn: Boolean) extends SlackAuthenticatedAction { // Long actually Id[LibraryToSlackChannel]
  type A = TurnLibraryPush
  def helper = TurnLibraryPush
}

object TurnChannelIngestion extends SlackAuthenticatedActionHelper[TurnChannelIngestion]("turn_channel_ingestion")
@json case class TurnChannelIngestion(integrationId: Long, turnOn: Boolean) extends SlackAuthenticatedAction { // Long actually Id[SlackChannelToLibrary]
  type A = TurnChannelIngestion
  def helper = TurnChannelIngestion
}

object AddSlackTeam extends SlackAuthenticatedActionHelper[AddSlackTeam]("add_slack_team")
@json case class AddSlackTeam(andThen: Option[SlackAuthenticatedAction]) extends SlackAuthenticatedAction {
  type A = AddSlackTeam
  def helper = AddSlackTeam
}

object ConnectSlackTeam extends SlackAuthenticatedActionHelper[ConnectSlackTeam]("connect_slack_team")
@json case class ConnectSlackTeam(organizationId: Id[Organization], andThen: Option[SlackAuthenticatedAction]) extends SlackAuthenticatedAction {
  type A = ConnectSlackTeam
  def helper = ConnectSlackTeam
}

object CreateSlackTeam extends SlackAuthenticatedActionHelper[CreateSlackTeam]("create_slack_team")
@json case class CreateSlackTeam(andThen: Option[SlackAuthenticatedAction]) extends SlackAuthenticatedAction {
  type A = CreateSlackTeam
  def helper = CreateSlackTeam
}

object SyncPublicChannels extends SlackAuthenticatedActionHelper[SyncPublicChannels]("sync_public_channels")
@json case class SyncPublicChannels(useKifiBot: Boolean) extends SlackAuthenticatedAction {
  type A = SyncPublicChannels
  def helper = SyncPublicChannels
}

object TurnCommentMirroring extends SlackAuthenticatedActionHelper[TurnCommentMirroring]("turn_comment_mirroring")
@json case class TurnCommentMirroring(turnOn: Boolean) extends SlackAuthenticatedAction {
  type A = TurnCommentMirroring
  def helper = TurnCommentMirroring
}

sealed abstract class SlackAuthenticatedActionHelper[A <: SlackAuthenticatedAction](val action: String) {
  implicit def helper: SlackAuthenticatedActionHelper[A] = this
}
object SlackAuthenticatedActionHelper {

  private val all: Set[SlackAuthenticatedActionHelper[_ <: SlackAuthenticatedAction]] = Set(
    Signup,
    Login,
    SetupLibraryIntegrations,
    TurnLibraryPush,
    TurnChannelIngestion,
    AddSlackTeam,
    ConnectSlackTeam,
    CreateSlackTeam,
    SyncPublicChannels,
    TurnCommentMirroring
  )

  implicit val format: Format[SlackAuthenticatedActionHelper[_ <: SlackAuthenticatedAction]] = Format(
    Reads(value => value.validate[String].flatMap[SlackAuthenticatedActionHelper[_ <: SlackAuthenticatedAction]](action =>
      all.find(_.action == action).map(JsSuccess(_)) getOrElse JsError(s"Unknown SlackAuthenticatedAction: $action"))
    ),
    Writes(helper => JsString(helper.action))
  )

  private def formatPure[A <: SlackAuthenticatedAction](a: A) = Format(Reads.pure(a), Writes[A](_ => Json.obj()))
  def getInstanceFormat[A <: SlackAuthenticatedAction](actionHelper: SlackAuthenticatedActionHelper[A]): Format[A] = actionHelper match {
    case Signup => formatPure(Signup())
    case Login => formatPure(Login())
    case SetupLibraryIntegrations => implicitly[Format[SetupLibraryIntegrations]]
    case TurnLibraryPush => implicitly[Format[TurnLibraryPush]]
    case TurnChannelIngestion => implicitly[Format[TurnChannelIngestion]]
    case AddSlackTeam => implicitly[Format[AddSlackTeam]]
    case ConnectSlackTeam => implicitly[Format[ConnectSlackTeam]]
    case CreateSlackTeam => implicitly[Format[CreateSlackTeam]]
    case SyncPublicChannels => implicitly[Format[SyncPublicChannels]]
    case TurnCommentMirroring => implicitly[Format[TurnCommentMirroring]]
  }

  private def getRequiredScopes(action: SlackAuthenticatedAction): Set[SlackAuthScope] = action match {
    case Signup() => SlackAuthScope.userSignup
    case Login() => SlackAuthScope.userLogin
    case SetupLibraryIntegrations(_, incomingWebhookId) => if (incomingWebhookId.isDefined) Set.empty else SlackAuthScope.integrationSetup
    case TurnLibraryPush(_, isBroken: Boolean, turnOn: Boolean) => if (turnOn && isBroken) SlackAuthScope.brokenPush else Set.empty
    case TurnChannelIngestion(_, turnOn) => if (turnOn) SlackAuthScope.ingest else Set.empty
    case AddSlackTeam(andThen) => SlackAuthScope.teamSetup ++ andThen.map(getRequiredScopes).getOrElse(Set.empty)
    case ConnectSlackTeam(_, andThen) => SlackAuthScope.teamSetup ++ andThen.map(getRequiredScopes).getOrElse(Set.empty)
    case CreateSlackTeam(andThen) => SlackAuthScope.teamSetup ++ andThen.map(getRequiredScopes).getOrElse(Set.empty)
    case SyncPublicChannels(useKifiBot) => if (useKifiBot) SlackAuthScope.syncPublicChannelsWithKifiBot else SlackAuthScope.syncPublicChannels
    case TurnCommentMirroring(turnOn) => if (turnOn) SlackAuthScope.pushAnywhereWithKifiBot else Set.empty
  }

  def getMissingScopes(action: SlackAuthenticatedAction, existingScopes: Set[SlackAuthScope]): Set[SlackAuthScope] = {
    val requiredScopes = SlackAuthenticatedActionHelper.getRequiredScopes(action)
    val missingScopes = requiredScopes -- existingScopes
    val requiresNewIncomingWebhook = requiredScopes.contains(SlackAuthScope.IncomingWebhook)
    if (requiresNewIncomingWebhook) missingScopes + SlackAuthScope.IncomingWebhook else missingScopes
  }
}

object SlackAuthenticatedAction {
  implicit val format: Format[SlackAuthenticatedAction] = Format(
    Reads { value =>
      for {
        helper <- (value \ "action").validate[SlackAuthenticatedActionHelper[_ <: SlackAuthenticatedAction]]
        action <- (value \ "data").validate(SlackAuthenticatedActionHelper.getInstanceFormat(helper))
      } yield action
    },
    Writes(action => Json.obj("action" -> action.helper, "data" -> SlackAuthenticatedActionHelper.getInstanceFormat(action.helper).writes(action.instance)))
  )
}

case class SlackAuthStateKey(state: SlackAuthState) extends Key[SlackAuthenticatedAction] {
  override val version = 1
  val namespace = "slack_action_by_state"
  def toKey(): String = state.state
}

class SlackAuthStateCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
    extends JsonCacheImpl[SlackAuthStateKey, SlackAuthenticatedAction](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
