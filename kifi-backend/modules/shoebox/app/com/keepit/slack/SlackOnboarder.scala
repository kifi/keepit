package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.core.anyExtensionOps
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.{ Logging, SlackLog }
import com.keepit.common.util.{ DescriptionElements, LinkElement }
import com.keepit.model._
import com.keepit.slack.models._

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[SlackOnboarderImpl])
trait SlackOnboarder {
  def talkAboutIntegration(integ: SlackIntegration): Future[Unit]
  def talkAboutTeam(team: SlackTeam): Future[Unit]
}

object SlackOnboarder {
  private val KifiSlackTeamId = SlackTeamId("T02A81H50")
  def canSendMessageAboutIntegration(integ: SlackIntegration): Boolean = integ match {
    case push: LibraryToSlackChannel => true
    case ingestion: SlackChannelToLibrary => ingestion.slackTeamId == KifiSlackTeamId
  }
}

@Singleton
class SlackOnboarderImpl @Inject() (
  db: Database,
  slackClient: SlackClientWrapper,
  pathCommander: PathCommander,
  libRepo: LibraryRepo,
  ktlRepo: KeepToLibraryRepo,
  keepRepo: KeepRepo,
  attributionRepo: KeepSourceAttributionRepo,
  implicit val executionContext: ExecutionContext,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends SlackOnboarder with Logging {

  val slackLog = new SlackLog(InhouseSlackChannel.TEST_RYAN)

  def talkAboutIntegration(integ: SlackIntegration): Future[Unit] = SafeFuture.swallow {
    log.info(s"[SLACK-ONBOARD] Maybe going to post a message about ${integ.slackChannelName} and ${integ.libraryId} by ${integ.slackUserId}")
    if (SlackOnboarder.canSendMessageAboutIntegration(integ)) {
      db.readOnlyMaster { implicit s =>
        generateOnboardingMessageForIntegration(integ)
      }.map { welcomeMsg =>
        log.info(s"[SLACK-ONBOARD] Generated this message: " + welcomeMsg)
        slackClient.sendToSlack(integ.slackUserId, integ.slackTeamId, integ.slackChannelName, welcomeMsg)
      }.getOrElse {
        log.info("[SLACK-ONBOARD] Could not generate a useful message, bailing")
        Future.successful(Unit)
      }
    } else {
      log.info("[SLACK-ONBOARD] Decided not to even try sending a message")
      Future.successful(Unit)
    }
  }

  private def generateOnboardingMessageForIntegration(integ: SlackIntegration)(implicit session: RSession): Option[SlackMessageRequest] = {
    import DescriptionElements._
    val textOpt: Option[DescriptionElements] = integ match {
      case ltsc: LibraryToSlackChannel =>
        val lib = libRepo.get(ltsc.libraryId)
        Some(DescriptionElements(
          "A new Kifi integration was just set up.",
          "Keeps from", lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute), "will be posted to this channel."
        ))
      case sctl: SlackChannelToLibrary =>
        val lib = libRepo.get(sctl.libraryId)
        val keepsFromSlack = keepRepo.getByIds(ktlRepo.getAllByLibraryId(sctl.libraryId).map(_.keepId).toSet).filter {
          case (keepId, keep) => keep.source == KeepSource.slack
        }
        val linksFromTargetChannel = attributionRepo.getByKeepIds(keepsFromSlack.keySet).collect {
          case (kId, SlackAttribution(slackMsg)) if sctl.slackChannelId.contains(slackMsg.channel.id) =>
            keepsFromSlack.get(kId).map(_.url)
        }.flatten.toSet
        Some(DescriptionElements(
          "We just collected a bunch of links from this channel (all", linksFromTargetChannel.size, "of them) and we'll keep collecting new ones as you post them :tornado:.",
          "You can browse them on Kifi in", lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute)
        )) tap { _.foreach(text => slackLog.info(s"Sending an ingestion to ${sctl.slackTeamId}.", text)) }
    }
    textOpt.map(text => SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(text)).quiet)
  }

  def talkAboutTeam(team: SlackTeam): Future[Unit] = ???
}
