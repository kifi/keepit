package com.keepit.controllers.admin

import akka.actor.ActorRef
import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.controller._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.json.EnumFormat
import com.keepit.common.reflection.Enumerator
import com.keepit.common.time.{Clock, _}
import com.keepit.slack.{SlackPersonalDigestNotificationGenerator, SlackPersonalDigestNotificationActor, SlackClientWrapper, SlackOnboarder}
import com.keepit.slack.models._
import com.kifi.juggle.ConcurrentTaskProcessingActor.IfYouCouldJustGoAhead
import org.joda.time.Period
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

// This class is exclusively for manual testing
object AdminEventTriggerController {
  sealed abstract class EventKind(val key: String) { def reads: Reads[_ <: EventTrigger] }
  object EventKind extends Enumerator[EventKind] {
    case object SlackTeamDigest extends EventKind("slack_team_digest") { def reads = EventTrigger.SlackTeamDigest.reads }
    case object SlackPersonalDigest extends EventKind("slack_personal_digest") { def reads = EventTrigger.SlackPersonalDigest.reads }
    case object SlackIntegrationsFTUIs extends EventKind("slack_integrations_ftuis") { def reads = EventTrigger.SlackIntegrationsFTUIs.reads }

    private val all = _all.toSet
    private def get(str: String): Option[EventKind] = all.find(_.key == str)
    implicit val format: Format[EventKind] = EnumFormat.format(get, _.key)
  }

  sealed trait EventTrigger
  object EventTrigger {
    case class SlackTeamDigest(team: SlackTeamId, user: SlackUserId) extends EventTrigger
    object SlackTeamDigest { val reads = Json.reads[SlackTeamDigest] }

    case class SlackPersonalDigest(team: SlackTeamId, user: SlackUserId, forceFirst: Boolean) extends EventTrigger
    object SlackPersonalDigest { val reads = Json.reads[SlackPersonalDigest] }

    case class SlackIntegrationsFTUIs(pushes: Set[Id[LibraryToSlackChannel]], ingestions: Set[Id[SlackChannelToLibrary]]) extends EventTrigger
    object SlackIntegrationsFTUIs { val reads = Json.reads[SlackIntegrationsFTUIs] }

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
  libraryToSlackChannelRepo: LibraryToSlackChannelRepo,
  slackChannelToLibraryRepo: SlackChannelToLibraryRepo,
  slackClient: SlackClientWrapper,
  slackPersonalDigestGenerator: SlackPersonalDigestNotificationGenerator,
  implicit val executionContext: ExecutionContext)
    extends AdminUserActions {
  import AdminEventTriggerController._

  def parseAndTriggerEvent() = AdminUserAction.async(parse.tolerantJson) { implicit request =>
    import AdminEventTriggerController.EventTrigger._
    val result = request.body.as[EventTrigger] match {
      case x: SlackTeamDigest => demoSlackTeamDigest(x)
      case x: SlackPersonalDigest => demoSlackPersonalDigest(x)
      case x: SlackIntegrationsFTUIs => forceSlackIntegrationsFTUIs(x)
    }
    result.map(Ok(_))
  }



  private def demoSlackTeamDigest(trigger: EventTrigger.SlackTeamDigest): Future[JsValue] = {
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

  private def demoSlackPersonalDigest(trigger: EventTrigger.SlackPersonalDigest): Future[JsValue] = {
    val now = clock.now
    db.readWriteAsync { implicit s =>
      for {
        team <- slackTeamRepo.getBySlackTeamId(trigger.team)
        membership <- slackMembershipRepo.getBySlackTeamAndUser(trigger.team, trigger.user).map { membership =>
          if (trigger.forceFirst) slackMembershipRepo.save(membership.copy(lastPersonalDigestAt = None))
          else membership
        }
      } yield (team, membership)
    }.map {
      case Some((team, membership)) =>
        val digestOpt = db.readOnlyMaster { implicit s => slackPersonalDigestGenerator.createPersonalDigest(membership) }
        digestOpt.fold(reasonForNoDigest => Json.obj("ok" -> false, "reason" -> reasonForNoDigest), { digest =>
          val msg = if (trigger.forceFirst) slackPersonalDigestGenerator.messageForFirstTimeDigest(digest)
          else slackPersonalDigestGenerator.messageForRegularDigest(digest)
          slackClient.sendToSlackHoweverPossible(trigger.team, trigger.user.asChannel, msg)
          Json.obj("ok" -> true)
        })
      case None =>
        Json.obj("ok" -> false, "err" -> "could not find team")
    }
  }

  private def forceSlackIntegrationsFTUIs(trigger: EventTrigger.SlackIntegrationsFTUIs): Future[JsValue] = {
    db.readOnlyReplicaAsync { implicit s =>
      val pushes = libraryToSlackChannelRepo.getActiveByIds(trigger.pushes)
      val ingestions = slackChannelToLibraryRepo.getActiveByIds(trigger.ingestions)
      (pushes, ingestions)
    }.flatMap {
      case (pushes, ingestions) => for {
        _ <- FutureHelpers.sequentialExec(pushes)(ltsc => slackOnboarder.talkAboutIntegration(ltsc))
        _ <- FutureHelpers.sequentialExec(ingestions)(sctl => slackOnboarder.talkAboutIntegration(sctl))
      } yield ()
    }.map { _: Unit =>
      Json.obj("ok" -> true)
    }.recover {
      case fail => Json.obj("ok" -> false, "err" -> fail.getMessage)
    }
  }
}
