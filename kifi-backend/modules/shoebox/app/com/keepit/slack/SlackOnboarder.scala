package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core.{ anyExtensionOps, optionExtensionOps }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.{ Logging, SlackLog }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time.Clock
import com.keepit.common.util.{ Debouncing, DescriptionElements, LinkElement }
import com.keepit.model._
import com.keepit.slack.SlackOnboarder.TeamOnboardingAgent
import com.keepit.slack.models._
import com.keepit.social.BasicUser
import org.joda.time.Period

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[SlackOnboarderImpl])
trait SlackOnboarder {
  def talkAboutIntegration(integ: SlackIntegration, forceOverride: Boolean = false): Future[Unit]
  def getTeamAgent(team: SlackTeam, membership: SlackTeamMembership, forceOverride: Boolean = false): TeamOnboardingAgent
}

object SlackOnboarder {
  val minDelayForExplicitMsg = Period.days(2)
  private val KifiSlackTeamId = SlackTeamId("T02A81H50")
  private val BrewstercorpSlackTeamId = SlackTeamId("T0FUL04N4")

  def canSendMessageAboutIntegration(integ: SlackIntegration): Boolean = integ match {
    case push: LibraryToSlackChannel => true
    case ingestion: SlackChannelToLibrary => true
  }

  def canSendMessageAboutTeam(team: SlackTeam): Boolean = team.slackTeamId == KifiSlackTeamId || team.slackTeamId == BrewstercorpSlackTeamId || team.slackTeamName.value.startsWith("eishaytest")

  val installationDescription = {
    import DescriptionElements._
    DescriptionElements(
      "Everyone can use the `/kifi <search words>` slash command to search through your links right from Slack. We'll even search the full content of the page.",
      "Install" --> LinkElement(PathCommander.browserExtension.absolute), "our Chrome and Firefox extensions for easy keeping and full Google integration.",
      "You'll also love our award winning (thanks Mom!)", "iOS" --> LinkElement(PathCommander.iOS), "and", "Android" --> LinkElement(PathCommander.android), "apps."
    )
  }

  class TeamOnboardingAgent(val team: SlackTeam, val membership: SlackTeamMembership, val forceOverride: Boolean)(implicit parent: SlackOnboarderImpl) {
    private var working = forceOverride || canSendMessageAboutTeam(team)
    def isWorking = working
    def dieIf(b: Boolean) = if (b) working = false

    def intro() = parent.teamAgent.intro(this)()
    def channels(channels: Seq[SlackChannelInfo]) = parent.teamAgent.channels(this)(channels)
    def ingesting() = parent.teamAgent.ingesting(this)()
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
  orgRepo: OrganizationRepo,
  attributionRepo: KeepSourceAttributionRepo,
  clock: Clock,
  implicit val executionContext: ExecutionContext,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends SlackOnboarder with Logging {

  private val debouncer = new Debouncing.Dropper[Future[Unit]]
  private val slackLog = new SlackLog(InhouseSlackChannel.ENG_SLACK)
  import SlackOnboarder._

  def talkAboutIntegration(integ: SlackIntegration, forceOverride: Boolean = false): Future[Unit] = SafeFuture.swallow {
    log.info(s"[SLACK-ONBOARD] Maybe going to post a message about ${integ.slackChannelName} and ${integ.libraryId} by ${integ.slackUserId}")
    if (forceOverride || canSendMessageAboutIntegration(integ)) {
      db.readOnlyMaster { implicit s =>
        generateOnboardingMessageForIntegration(integ)
      }.flatMap { welcomeMsg =>
        log.info(s"[SLACK-ONBOARD] Generated this message: " + welcomeMsg)
        debouncer.debounce(s"${integ.slackTeamId.value}_${integ.slackChannelName.value}", Period.minutes(10)) {
          slackLog.info(s"Sent a welcome message to channel ${integ.slackChannelName} in ${integ.slackTeamId}")
          slackClient.sendToSlack(integ.slackUserId, integ.slackTeamId, (integ.slackChannelName, integ.slackChannelId), welcomeMsg)
        }
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
    val slackTeamForLibrary = slackTeamRepo.getBySlackTeamId(integ.slackTeamId).filter(_.organizationId containsTheSameValueAs lib.organizationId)

    for {
      owner <- slackTeamMembershipRepo.getBySlackTeamAndUser(integ.slackTeamId, integ.slackUserId).flatMap(_.userId).map(basicUserRepo.load)
      msg <- (integ, slackTeamForLibrary) match {
        case (ltsc: LibraryToSlackChannel, Some(slackTeam)) if lib.kind == LibraryKind.USER_CREATED =>
          explicitPushMessage(ltsc, owner, lib, slackTeam)
        case (sctl: SlackChannelToLibrary, Some(slackTeam)) if lib.kind == LibraryKind.USER_CREATED || (sctl.slackChannelId containsTheSameValueAs slackTeam.generalChannelId) =>
          explicitIngestionMessage(sctl, owner, lib, slackTeam)
        case (sctl: SlackChannelToLibrary, Some(slackTeam)) if lib.kind == LibraryKind.SYSTEM_ORG_GENERAL && (sctl.slackChannelId containsTheSameValueAs slackTeam.generalChannelId) =>
          generalLibraryMessage(sctl, owner, lib, slackTeam)
        case (sctl: SlackChannelToLibrary, _) => None // for ingestions to any other type of library, stay quiet
        case (ltsc: LibraryToSlackChannel, _) =>
          conservativePushMessage(ltsc, owner, lib)
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
      "Keeps added to", lib.name, "will be posted here", SlackEmoji.fireworks
    ))
    val attachments = List(
      SlackAttachment(text = Some(DescriptionElements.formatForSlack(DescriptionElements.unlines(List(
        DescriptionElements(SlackEmoji.magnifyingGlass, "*Searching Links*"),
        installationDescription
      ))))).withFullMarkdown,
      SlackAttachment(text = Some(DescriptionElements.formatForSlack(DescriptionElements.unlines(List(
        DescriptionElements(SlackEmoji.constructionWorker, "*Managing Links*"),
        DescriptionElements(
          "Go to", "Kifi" --> LinkElement(pathCommander.libraryPageViaSlack(lib, slackTeam.slackTeamId).absolute), "to access all your links.",
          "If you don't have a Kifi account, you can sign up with your Slack account in just 20 seconds."
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
        DescriptionElements("Any time someone includes a link in their message, we'll automatically save it and index the site so you can search for it easily.")
      ))))).withFullMarkdown,
      SlackAttachment(text = Some(DescriptionElements.formatForSlack(DescriptionElements.unlines(List(
        DescriptionElements(SlackEmoji.magnifyingGlass, "*Searching Links*"),
        installationDescription
      ))))).withFullMarkdown,
      SlackAttachment(text = Some(DescriptionElements.formatForSlack(DescriptionElements.unlines(List(
        DescriptionElements(SlackEmoji.constructionWorker, "*Managing Links*"),
        DescriptionElements(
          "Go to", "Kifi" --> LinkElement(pathCommander.libraryPageViaSlack(lib, slackTeam.slackTeamId).absolute), "to access all your links.",
          "If you don't have a Kifi account, you can sign up with your Slack account in just 20 seconds."
        )
      ))))).withFullMarkdown
    )
    val msg = SlackMessageRequest.fromKifi(text = txt, attachments).quiet
    Some(msg)
  }

  private def generalLibraryMessage(sctl: SlackChannelToLibrary, owner: BasicUser, lib: Library, slackTeam: SlackTeam)(implicit session: RSession): Option[SlackMessageRequest] = {
    import DescriptionElements._
    val txt = DescriptionElements.formatForSlack(DescriptionElements(
      owner, "connected", sctl.slackChannelName.value, "with", s"${lib.name} on Kifi" --> LinkElement(pathCommander.libraryPageViaSlack(lib, slackTeam.slackTeamId).absolute),
      "to auto-magically manage links", SlackEmoji.fireworks
    ))
    val attachments = List(
      SlackAttachment(text = Some(DescriptionElements.formatForSlack(DescriptionElements.unlines(List(
        DescriptionElements(
          SlackEmoji.clipboard, s"Get on", "Kifi" --> LinkElement(pathCommander.browserExtensionViaSlack(slackTeam.slackTeamId).absolute),
          "to access your automatically organized lists of links."
        ),
        DescriptionElements("Libraries have been created for each channel integrated. Join Kifi to access your lists of links messaged in Slack")
      ))))).withFullMarkdown,
      SlackAttachment(text = Some(DescriptionElements.formatForSlack(DescriptionElements.unlines(List(
        DescriptionElements(SlackEmoji.star, s"Automatically save links from your #channels"),
        DescriptionElements("Every time someone includes a link in a chat message, we'll save it and capture every word on the page. It'll be automatically archived and searchable.")
      ))))).withFullMarkdown,
      SlackAttachment(text = Some(DescriptionElements.formatForSlack(DescriptionElements.unlines(List(
        DescriptionElements(SlackEmoji.magnifyingGlass, "Searching: Find your links in Slack and Google"),
        DescriptionElements(
          "Everyone can use the slash command `[/kifi <search term>]` to search on Slack. Install our Chrome and Firefox extensions to",
          "get keeps in your Google Search results" --> LinkElement(pathCommander.browserExtensionViaSlack(slackTeam.slackTeamId).absolute), ".",
          "You'll also love our award winning (thanks Mom!)", "iOS" --> LinkElement(PathCommander.iOS), "and", "Android apps" --> LinkElement(PathCommander.android)
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
    require(ltsc.libraryId == lib.id.get)
    import DescriptionElements._
    val txt = DescriptionElements.formatForSlack(DescriptionElements(
      owner, "set up a Kifi integration.",
      "Keeps from", lib.name --> LinkElement(pathCommander.libraryPage(lib).absolute), "will be posted to this channel."
    ))
    val msg = SlackMessageRequest.fromKifi(text = txt).quiet
    Some(msg)
  }

  def getTeamAgent(team: SlackTeam, membership: SlackTeamMembership, forceOverride: Boolean = false): TeamOnboardingAgent = {
    new TeamOnboardingAgent(team, membership, forceOverride)(this)
  }
  object teamAgent {
    def intro(agent: TeamOnboardingAgent)(): Future[Try[Unit]] = FutureHelpers.robustly {
      (for {
        msg <- Some(SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(DescriptionElements(
          "We're talking to Slack to get your public channels now.", SlackEmoji.rocket,
          agent.team.publicChannelsLastSyncedAt.map(time => DescriptionElements("Last time we did this was", time)).getOrElse {
            DescriptionElements("This might take a few moments, especially if you have a lot of channels.", SlackEmoji.hourglass)
          }
        )))).filter(_ => agent.isWorking)
      } yield {
        slackClient.sendToSlack(agent.membership.slackUserId, agent.membership.slackTeamId, agent.membership.slackUserId.asChannel, msg).andThen(logFTUI(agent, msg))
      }) getOrElse {
        slackLog.info(s"Decided not to send a FTUI to team ${agent.team.slackTeamName.value}")
        Future.successful(Unit)
      }
    }
    def channels(agent: TeamOnboardingAgent)(channels: Seq[SlackChannelInfo]): Future[Try[Unit]] = FutureHelpers.robustly {
      (for {
        msg <- Some(SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(DescriptionElements(
          "Slack told us you have",
          if (channels.nonEmpty) DescriptionElements(channels.length, "new channels.", "We're creating Kifi libraries for them now.")
          else DescriptionElements("no new channels. Sorry we couldn't be more helpful.")
        )))).filter(_ => agent.isWorking)
      } yield {
        agent.dieIf(channels.isEmpty)
        slackClient.sendToSlack(agent.membership.slackUserId, agent.membership.slackTeamId, agent.membership.slackUserId.asChannel, msg).andThen(logFTUI(agent, msg))
      }) getOrElse {
        slackLog.info(s"Decided not to send a FTUI to team ${agent.team.slackTeamName.value}")
        Future.successful(Unit)
      }
    }
    def ingesting(agent: TeamOnboardingAgent)(): Future[Try[Unit]] = FutureHelpers.robustly {
      import DescriptionElements._
      (for {
        org <- agent.team.organizationId.map(orgId => db.readOnlyMaster { implicit s => orgRepo.get(orgId) })
        msg <- Some(SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(DescriptionElements(
          "We're done creating those new libraries.",
          "You can browse through them at the", org.name --> LinkElement(pathCommander.orgLibrariesPage(org).absolute), "page on Kifi."
        )))).filter(_ => agent.isWorking)
      } yield {
        slackClient.sendToSlack(agent.membership.slackUserId, agent.membership.slackTeamId, agent.membership.slackUserId.asChannel, msg).andThen(logFTUI(agent, msg))
      }) getOrElse {
        slackLog.info(s"Decided not to send a FTUI to team ${agent.team.slackTeamName.value}")
        Future.successful(Unit)
      }
    }
    private def logFTUI(agent: TeamOnboardingAgent, msg: SlackMessageRequest): PartialFunction[Try[Unit], Unit] = {
      case Success(_: Unit) => slackLog.info("Pushed a team FTUI to", agent.membership.slackUsername.value, "in", agent.team.slackTeamName.value, "saying", msg.text)
      case Failure(fail) => slackLog.warn("Failed to push team FTUI to", agent.membership.slackUsername.value, "in", agent.team.slackTeamName.value, "because:", fail.getMessage)
    }
  }
}
