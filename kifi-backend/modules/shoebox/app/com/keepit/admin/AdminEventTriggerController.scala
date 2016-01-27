package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller._
import com.keepit.common.db.slick.Database
import com.keepit.common.json.EnumFormat
import com.keepit.common.reflection.Enumerator
import com.keepit.common.time.{Clock, _}
import com.keepit.slack.SlackOnboarder
import com.keepit.slack.models._
import org.joda.time.Period
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

// This class is exclusively for manual testing
object AdminEventTriggerController {
  sealed abstract class EventKind(val key: String) { def reads: Reads[_ <: EventTrigger] }
  object EventKind extends Enumerator[EventKind] {
    case object SlackTeamDigest extends EventKind("slack_team_digest") { def reads = EventTrigger.SlackTeamDigest.reads }
    case object SlackChannelDigest extends EventKind("slack_channel_digest") { def reads = EventTrigger.SlackChannelDigest.reads }
    case object SlackTeamFTUI extends EventKind("slack_team_ftui") { def reads = EventTrigger.SlackTeamFTUI.reads }

    private val all = _all.toSet
    private def get(str: String): Option[EventKind] = all.find(_.key == str)
    implicit val format: Format[EventKind] = EnumFormat.format(get, _.key)
  }

  sealed trait EventTrigger
  object EventTrigger {
    case class SlackTeamDigest(team: SlackTeamId, user: SlackUserId) extends EventTrigger
    object SlackTeamDigest { val reads = Json.reads[SlackTeamDigest] }

    case class SlackChannelDigest(team: SlackTeamId, channel: SlackChannelId) extends EventTrigger
    object SlackChannelDigest { val reads = Json.reads[SlackChannelDigest] }

    case class SlackTeamFTUI(team: SlackTeamId, user: SlackUserId) extends EventTrigger
    object SlackTeamFTUI { val reads = Json.reads[SlackTeamFTUI] }

    implicit val reads: Reads[EventTrigger] = Reads { j =>
      for {
        kind <- (j \ "kind").validate[EventKind]
        v <- kind.reads.reads(j)
      } yield v
    }
  }

}

class AdminEventTriggerController @Inject() (
  db: Database,
  clock: Clock,
  val userActionsHelper: UserActionsHelper,
  slackOnboarder: SlackOnboarder,
  slackTeamRepo: SlackTeamRepo,
  slackChannelRepo: SlackChannelRepo,
  slackMembershipRepo: SlackTeamMembershipRepo,
  implicit val executionContext: ExecutionContext)
    extends AdminUserActions {
  import AdminEventTriggerController._

  def parseAndTriggerEvent() = AdminUserAction.async(parse.tolerantJson) { implicit request =>
    import AdminEventTriggerController.EventTrigger._
    val result = request.body.as[EventTrigger] match {
      case x: SlackTeamDigest => forceSlackTeamDigest(x)
      case x: SlackChannelDigest => ???
      case x: SlackTeamFTUI => forceSlackTeamFTUI(x)
    }
    result.map(Ok(_))
  }



  private def forceSlackTeamDigest(trigger: EventTrigger.SlackTeamDigest): Future[JsValue] = {
    db.readWriteAsync { implicit s =>
      slackTeamRepo.getBySlackTeamId(trigger.team).map { team =>
        slackTeamRepo.save(team.withLastDigestNotificationAt(clock.now minus Period.years(5)))
      }
    }.map { teamOpt =>
      teamOpt.map { team =>
        Json.obj("ok" -> true)
      }.getOrElse {
        Json.obj("ok" -> false, "err" -> "could not find team")
      }
    }
  }

  private def forceSlackTeamFTUI(trigger: EventTrigger.SlackTeamFTUI): Future[JsValue] = {
    db.readOnlyReplica { implicit s =>
      val teamOpt = slackTeamRepo.getBySlackTeamId(trigger.team)
      val membershipOpt = slackMembershipRepo.getBySlackTeamAndUser(trigger.team, trigger.user)
      for { team <- teamOpt; membership <- membershipOpt } yield (team, membership)
    }.map {
      case (team, membership) => slackOnboarder.talkAboutTeam(team, membership, forceOverride = true).map { _ => Json.obj("ok" -> true) }
    }.getOrElse {
      Future.successful(Json.obj("ok" -> false, "err" -> "could not find team and membership"))
    }
  }
}
