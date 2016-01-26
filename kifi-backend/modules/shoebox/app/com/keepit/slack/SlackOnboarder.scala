package com.keepit.slack

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.core.anyExtensionOps
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.{ Logging, SlackLog }
import com.keepit.common.core.{ anyExtensionOps, optionExtensionOps }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.{ Logging, SlackLog }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.util.{ DescriptionElements, LinkElement }
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.social.BasicUser

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
  slackTeamRepo: SlackTeamRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  basicUserRepo: BasicUserRepo,
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
    val lib = libRepo.get(integ.libraryId)
    val slackTeamForLibrary = slackTeamRepo.getBySlackTeamId(integ.slackTeamId).filter(_.organizationId hasTheSameValueAs lib.organizationId)

    for {
      owner <- slackTeamMembershipRepo.getBySlackTeamAndUser(integ.slackTeamId, integ.slackUserId).flatMap(_.userId).map(basicUserRepo.load)
      text <- (integ, slackTeamForLibrary) match {
        case (ltsc: LibraryToSlackChannel, Some(slackTeam)) => explicitPushMessage(ltsc, owner, lib, slackTeam)
        case (sctl: SlackChannelToLibrary, Some(slackTeam)) => explicitIngestionMessage(sctl, owner, lib, slackTeam)
        case (ltsc: LibraryToSlackChannel, None) => conservativePushMessage(ltsc, owner, lib)
        case (sctl: SlackChannelToLibrary, None) => conservativeIngestionMessage(sctl, owner, lib)
      }
    } yield SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(text)).quiet
  }

  private def explicitPushMessage(ltsc: LibraryToSlackChannel, owner: BasicUser, lib: Library, slackTeam: SlackTeam): Option[DescriptionElements] = {
    // TODO(ryan): actually make this more explicit
    conservativePushMessage(ltsc, owner, lib)
  }
  private def conservativePushMessage(ltsc: LibraryToSlackChannel, owner: BasicUser, lib: Library): Option[DescriptionElements] = {
    import DescriptionElements._
    Some(DescriptionElements(
      owner, "set up a Kifi integration.",
      "Keeps from", lib.name --> LinkElement(pathCommander.libraryPageViaSlack(lib, ltsc.slackTeamId).absolute), "will be posted to this channel."
    ))
  }
  private def conservativeIngestionMessage(sctl: SlackChannelToLibrary, owner: BasicUser, lib: Library)(implicit session: RSession): Option[DescriptionElements] = {
    import DescriptionElements._
    val keepsFromSlack = keepRepo.getByIds(ktlRepo.getAllByLibraryId(sctl.libraryId).map(_.keepId).toSet).filter {
      case (keepId, keep) => keep.source == KeepSource.slack
    }
    val linksFromTargetChannel = attributionRepo.getByKeepIds(keepsFromSlack.keySet).collect {
      case (kId, SlackAttribution(slackMsg)) if sctl.slackChannelId.contains(slackMsg.channel.id) =>
        keepsFromSlack.get(kId).map(_.url)
    }.flatten.toSet
    Some(DescriptionElements(
      "We just collected a bunch of links from this channel (", linksFromTargetChannel.size, "in all) and we'll keep collecting new ones as you post them :tornado:.",
      "You can browse them in", lib.name --> LinkElement(pathCommander.libraryPageViaSlack(lib, sctl.slackTeamId).absolute)
    )).filter(_ => sctl.slackTeamId == SlackDigestNotifier.KifiSlackTeamId) tap { _.foreach(text => slackLog.info(s"Sending an ingestion to ${sctl.slackTeamId}.", text)) }
  }
  private def explicitIngestionMessage(sctl: SlackChannelToLibrary, owner: BasicUser, lib: Library, slackTeam: SlackTeam)(implicit session: RSession): Option[DescriptionElements] = {
    // TODO(ryan): actually make this more explicit
    conservativeIngestionMessage(sctl, owner, lib)
  }

  def talkAboutTeam(team: SlackTeam): Future[Unit] = ???
}
