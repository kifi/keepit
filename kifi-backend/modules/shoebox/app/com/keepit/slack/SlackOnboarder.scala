package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.core.{ anyExtensionOps, optionExtensionOps }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.{ Logging, SlackLog }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time.{ Clock, _ }
import com.keepit.common.util.{ DescriptionElements, LinkElement }
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.social.BasicUser
import org.joda.time.Period

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[SlackOnboarderImpl])
trait SlackOnboarder {
  def talkAboutIntegration(integ: SlackIntegration): Future[Unit]
  def talkAboutTeam(team: SlackTeam): Future[Unit]
}

object SlackOnboarder {
  val minDelayForExplicitMsg = Period.days(2)
  private val KifiSlackTeamId = SlackTeamId("T02A81H50")
  private val BrewstercorpSlackTeamId = SlackTeamId("T0FUL04N4")

  def getsNewFTUI(slackTeamId: SlackTeamId): Boolean = slackTeamId == KifiSlackTeamId || slackTeamId == BrewstercorpSlackTeamId

  def canSendMessageAboutIntegration(integ: SlackIntegration): Boolean = integ match {
    case push: LibraryToSlackChannel => true
    case ingestion: SlackChannelToLibrary => ingestion.slackTeamId == KifiSlackTeamId || ingestion.slackTeamId == BrewstercorpSlackTeamId
  }

  val installationDescription = {
    import DescriptionElements._
    DescriptionElements(
      "Use the `/kifi <search words>` command. We'll even search the full content of the page.",
      "Install" --> LinkElement(PathCommander.browserExtension.absolute), "our Chrome and Firefox extensions for easy keeping and full Google integration.",
      "You'll also love our award winning (thanks Mom!)", "iOS" --> LinkElement(PathCommander.iOS), "and", "Android" --> LinkElement(PathCommander.android), "apps."
    )
  }
}

@Singleton
class SlackOnboarderImpl @Inject() (
  db: Database,
  slackClient: SlackClientWrapper,
  pathCommander: PathCommander,
  slackChannelRepo: SlackChannelRepo,
  slackTeamRepo: SlackTeamRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  basicUserRepo: BasicUserRepo,
  libRepo: LibraryRepo,
  ktlRepo: KeepToLibraryRepo,
  keepRepo: KeepRepo,
  attributionRepo: KeepSourceAttributionRepo,
  clock: Clock,
  implicit val executionContext: ExecutionContext,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends SlackOnboarder with Logging {

  val slackLog = new SlackLog(InhouseSlackChannel.TEST_RYAN)
  import SlackOnboarder._

  def talkAboutIntegration(integ: SlackIntegration): Future[Unit] = SafeFuture.swallow {
    log.info(s"[SLACK-ONBOARD] Maybe going to post a message about ${integ.slackChannelName} and ${integ.libraryId} by ${integ.slackUserId}")
    if (canSendMessageAboutIntegration(integ)) {
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
    val channel = integ.slackChannelId.flatMap(channelId => slackChannelRepo.getByChannelId(integ.slackTeamId, channelId))
    val slackTeamForLibrary = slackTeamRepo.getBySlackTeamId(integ.slackTeamId).filter(_.organizationId hasTheSameValueAs lib.organizationId)
    val explicitMsgCutoff = clock.now minus minDelayForExplicitMsg

    for {
      owner <- slackTeamMembershipRepo.getBySlackTeamAndUser(integ.slackTeamId, integ.slackUserId).flatMap(_.userId).map(basicUserRepo.load)
      msg <- (integ, slackTeamForLibrary) match {
        case (ltsc: LibraryToSlackChannel, _) if !getsNewFTUI(integ.slackTeamId) =>
          oldSchoolPushMessage(ltsc, owner, lib)
        case _ if !getsNewFTUI(integ.slackTeamId) =>
          None
        case (ltsc: LibraryToSlackChannel, Some(slackTeam)) if !channel.exists(_.lastNotificationAt isAfter explicitMsgCutoff) =>
          explicitPushMessage(ltsc, owner, lib, slackTeam)
        case (sctl: SlackChannelToLibrary, Some(slackTeam)) if !channel.exists(_.lastNotificationAt isAfter explicitMsgCutoff) =>
          explicitIngestionMessage(sctl, owner, lib, slackTeam)
        case (ltsc: LibraryToSlackChannel, _) =>
          conservativePushMessage(ltsc, owner, lib)
        case (sctl: SlackChannelToLibrary, _) =>
          conservativeIngestionMessage(sctl, owner, lib)
      }
    } yield msg
  }

  private def conservativePushMessage(ltsc: LibraryToSlackChannel, owner: BasicUser, lib: Library)(implicit session: RSession): Option[SlackMessageRequest] = {
    import DescriptionElements._
    val txt = DescriptionElements.formatForSlack(DescriptionElements(
      owner, "set up a Kifi integration.",
      "Keeps from", lib.name --> LinkElement(pathCommander.libraryPageViaSlack(lib, ltsc.slackTeamId).absolute), "will be posted to this channel."
    ))
    val msg = SlackMessageRequest.fromKifi(text = txt).quiet
    Some(msg)
  }
  private def explicitPushMessage(ltsc: LibraryToSlackChannel, owner: BasicUser, lib: Library, slackTeam: SlackTeam)(implicit session: RSession): Option[SlackMessageRequest] = {
    import DescriptionElements._
    val txt = DescriptionElements.formatForSlack(DescriptionElements(
      owner, "connected", ltsc.slackChannelName.value, "with", lib.name --> LinkElement(pathCommander.libraryPageViaSlack(lib, slackTeam.slackTeamId)), ".",
      "Links added from to", lib.name, "will be posted here", SlackEmoji.fireworks
    ))
    val attachments = List(
      SlackAttachment(text = Some(DescriptionElements.formatForSlack(DescriptionElements.unlines(List(
        DescriptionElements(SlackEmoji.magnifyingGlass, "*Searching Links*"),
        installationDescription
      ))))).withFullMarkdown,
      SlackAttachment(text = Some(DescriptionElements.formatForSlack(DescriptionElements.unlines(List(
        DescriptionElements(SlackEmoji.constructionWorker, "*Managing Links*"),
        DescriptionElements(
          "Here's the library:", lib.name --> LinkElement(pathCommander.libraryPageViaSlack(lib, slackTeam.slackTeamId).absolute), ".",
          "If you don't have a Kifi account, setup is just 20 seconds. From here, you can add/remove/move links."
        )
      ))))).withFullMarkdown
    )
    val msg = SlackMessageRequest.fromKifi(text = txt, attachments).quiet
    Some(msg)
  }

  private def conservativeIngestionMessage(sctl: SlackChannelToLibrary, owner: BasicUser, lib: Library)(implicit session: RSession): Option[SlackMessageRequest] = {
    import DescriptionElements._
    val txt = DescriptionElements(
      "Links posted here will be automatically kept in", lib.name --> LinkElement(pathCommander.libraryPageViaSlack(lib, sctl.slackTeamId).absolute),
      "on", "Kifi" // TODO(ryan): this is supposed to be linked to something. Home page?
    )
    val msg = SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(txt)).quiet
    Some(msg)
  }
  private def explicitIngestionMessage(sctl: SlackChannelToLibrary, owner: BasicUser, lib: Library, slackTeam: SlackTeam)(implicit session: RSession): Option[SlackMessageRequest] = {
    import DescriptionElements._
    val txt = DescriptionElements.formatForSlack(DescriptionElements(
      owner, "connected", sctl.slackChannelName.value, "with", lib.name --> LinkElement(pathCommander.libraryPageViaSlack(lib, slackTeam.slackTeamId).absolute),
      "on Kifi to auto-magically manage links", SlackEmoji.fireworks
    ))
    val attachments = List(
      SlackAttachment(text = Some(DescriptionElements.formatForSlack(DescriptionElements.unlines(List(
        DescriptionElements(SlackEmoji.star, s"*Saving links from ${sctl.slackChannelName.value}*"),
        DescriptionElements("Every time someone posts a link here, it'll be automatically saved in this library.")
      ))))).withFullMarkdown,
      SlackAttachment(text = Some(DescriptionElements.formatForSlack(DescriptionElements.unlines(List(
        DescriptionElements(SlackEmoji.magnifyingGlass, "*Searching Links*"),
        installationDescription
      ))))).withFullMarkdown,
      SlackAttachment(text = Some(DescriptionElements.formatForSlack(DescriptionElements.unlines(List(
        DescriptionElements(SlackEmoji.constructionWorker, "*Managing Links*"),
        DescriptionElements(
          "Here's the library:", lib.name --> LinkElement(pathCommander.libraryPageViaSlack(lib, slackTeam.slackTeamId).absolute), ".",
          "If you don't have a Kifi account, setup is just 20 seconds. From here, you can add/remove/move links."
        )
      ))))).withFullMarkdown
    )
    val msg = SlackMessageRequest.fromKifi(text = txt, attachments).quiet
    Some(msg)
  }

  private def doneIngestingMessage(sctl: SlackChannelToLibrary, owner: BasicUser, lib: Library)(implicit session: RSession): Option[DescriptionElements] = {
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
    )) tap { _.foreach(text => slackLog.info(s"Sending an ingestion to ${sctl.slackTeamId}.", text)) }
  }

  private def oldSchoolPushMessage(ltsc: LibraryToSlackChannel, owner: BasicUser, lib: Library)(implicit session: RSession): Option[SlackMessageRequest] = {
    import DescriptionElements._
    val txt = DescriptionElements.formatForSlack(DescriptionElements(
      owner, "set up a Kifi integration.",
      "Keeps from", lib.name --> LinkElement(pathCommander.libraryPage(lib).absolute), "will be posted to this channel."
    ))
    val msg = SlackMessageRequest.fromKifi(text = txt).quiet
    Some(msg)
  }

  def talkAboutTeam(team: SlackTeam): Future[Unit] = ???
}
