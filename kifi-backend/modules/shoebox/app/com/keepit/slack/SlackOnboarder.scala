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

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }
import scala.concurrent.duration._

@ImplementedBy(classOf[SlackOnboarderImpl])
trait SlackOnboarder {
  def talkAboutIntegration(integ: SlackIntegration): Future[Unit]
  def getTeamAgent(team: SlackTeam, membership: SlackTeamMembership, forceOverride: Boolean = false): TeamOnboardingAgent
}

object SlackOnboarder {
  val installationDescription = {
    import DescriptionElements._
    DescriptionElements(
      "Everyone can use the `/kifi <search words>` slash command to search through your links right from Slack. We'll even search the full content of the page.",
      "Install" --> LinkElement(PathCommander.browserExtension), "our Chrome and Firefox extensions for easy keeping and full Google integration.",
      "You'll also love our award winning (thanks Mom!)", "iOS" --> LinkElement(PathCommander.iOS), "and", "Android" --> LinkElement(PathCommander.android), "apps."
    )
  }

  class TeamOnboardingAgent(val team: SlackTeam, val membership: SlackTeamMembership, val forceOverride: Boolean)(implicit parent: SlackOnboarderImpl) {
    private var working = true
    def isWorking = working
    def dieIf(b: Boolean) = if (b) working = false

    def intro() = parent.teamAgent.intro(this)()
    def channels(membership: SlackTeamMembership, channels: Seq[SlackPublicChannelInfo]) = parent.teamAgent.channels(this)(membership, channels)
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
  orgConfigRepo: OrganizationConfigurationRepo,
  attributionRepo: KeepSourceAttributionRepo,
  clock: Clock,
  implicit val executionContext: ExecutionContext,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends SlackOnboarder with Logging {

  private val debouncer = new Debouncing.Dropper[Future[Unit]]
  private val slackLog = new SlackLog(InhouseSlackChannel.ENG_SLACK)
  import SlackOnboarder._

  def talkAboutIntegration(integ: SlackIntegration): Future[Unit] = SafeFuture.swallow {
    db.readOnlyMaster { implicit s =>
      generateOnboardingMessageForIntegration(integ)
    }.flatMap { welcomeMsg =>
      log.info(s"[SLACK-ONBOARD] Generated this message: " + welcomeMsg)
      debouncer.debounce(s"${integ.slackTeamId.value}_${integ.slackChannelName.value}", 10 minutes) {
        slackLog.info(s"Sent a welcome message to channel ${integ.slackChannelName} saying", welcomeMsg.text)
        slackClient.sendToSlackViaUser(integ.slackUserId, integ.slackTeamId, (integ.slackChannelName, integ.slackChannelId), welcomeMsg).map(_ => ())
      }
    }.getOrElse {
      log.info(s"[SLACK-ONBOARD] Decided not to send an onboarding message to ${integ.slackChannelName} in ${integ.slackTeamId}")
      Future.successful(())
    }
  }

  private def generateOnboardingMessageForIntegration(integ: SlackIntegration)(implicit session: RSession): Option[SlackMessageRequest] = {
    val lib = libRepo.get(integ.libraryId)
    val slackTeamOpt = slackTeamRepo.getBySlackTeamId(integ.slackTeamId)
    val allowedToSendToSlackTeam = slackTeamOpt.exists { team =>
      team.organizationId.map(orgConfigRepo.getByOrgId).exists { config =>
        config.settings.settingFor(StaticFeature.SlackNotifications).contains(StaticFeatureSetting.ENABLED)
      }
    }
    if (!allowedToSendToSlackTeam) None
    else slackTeamMembershipRepo.getBySlackTeamAndUser(integ.slackTeamId, integ.slackUserId).flatMap(_.userId).map(basicUserRepo.load).flatMap { owner =>
      slackTeamOpt match {
        case Some(slackTeam) if slackTeam.organizationId containsTheSameValueAs lib.organizationId =>
          // We can be more explicit about what is happening
          integ match {
            case ltsc: LibraryToSlackChannel if lib.kind == LibraryKind.USER_CREATED =>
              explicitPushMessage(ltsc, owner, lib, slackTeam)
            case sctl: SlackChannelToLibrary if lib.kind == LibraryKind.USER_CREATED =>
              explicitIngestionMessage(sctl, owner, lib, slackTeam)
            case _ => None
          }
        case _ =>
          // be very conservative, this integration is not on one of this team's org libraries
          integ match {
            case ltsc: LibraryToSlackChannel =>
              conservativePushMessage(ltsc, owner, lib)
            case _ => None
          }
      }
    }
  }

  private def conservativePushMessage(ltsc: LibraryToSlackChannel, owner: BasicUser, lib: Library)(implicit session: RSession): Option[SlackMessageRequest] = {
    import DescriptionElements._
    val txt = DescriptionElements.formatForSlack(DescriptionElements(
      owner.firstName --> LinkElement(pathCommander.welcomePageViaSlack(owner, ltsc.slackTeamId)), "set up a Kifi integration.",
      "Keeps from", lib.name --> LinkElement(pathCommander.libraryPageViaSlack(lib, ltsc.slackTeamId)), "will be posted to this channel."
    ))
    val msg = SlackMessageRequest.fromKifi(text = txt).quiet
    Some(msg)
  }
  private def explicitPushMessage(ltsc: LibraryToSlackChannel, owner: BasicUser, lib: Library, slackTeam: SlackTeam)(implicit session: RSession): Option[SlackMessageRequest] = {
    import DescriptionElements._
    val txt = DescriptionElements.formatForSlack(DescriptionElements(
      owner.firstName --> LinkElement(pathCommander.welcomePageViaSlack(owner, ltsc.slackTeamId)), "connected",
      ltsc.slackChannelName.value, "with", lib.name --> LinkElement(pathCommander.libraryPageViaSlack(lib, slackTeam.slackTeamId)), ".",
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
          "Go to", "Kifi" --> LinkElement(pathCommander.libraryPageViaSlack(lib, slackTeam.slackTeamId)), "to access all your links.",
          "If you don't have a Kifi account, you can sign up with your Slack account in just 20 seconds."
        )
      ))))).withFullMarkdown
    )
    val msg = SlackMessageRequest.fromKifi(text = txt, attachments).quiet
    Some(msg)
  }

  private def explicitIngestionMessage(sctl: SlackChannelToLibrary, owner: BasicUser, lib: Library, slackTeam: SlackTeam)(implicit session: RSession): Option[SlackMessageRequest] = {
    import DescriptionElements._
    val txt = DescriptionElements.formatForSlack(DescriptionElements(
      owner.firstName --> LinkElement(pathCommander.welcomePageViaSlack(owner, sctl.slackTeamId)), "connected", sctl.slackChannelName.value, "with", lib.name --> LinkElement(pathCommander.libraryPageViaSlack(lib, slackTeam.slackTeamId)),
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
          "Go to", "Kifi" --> LinkElement(pathCommander.libraryPageViaSlack(lib, slackTeam.slackTeamId)), "to access all your links.",
          "If you don't have a Kifi account, you can sign up with your Slack account in just 20 seconds."
        )
      ))))).withFullMarkdown
    )
    val msg = SlackMessageRequest.fromKifi(text = txt, attachments).quiet
    Some(msg)
  }

  def getTeamAgent(team: SlackTeam, membership: SlackTeamMembership, forceOverride: Boolean = false): TeamOnboardingAgent = {
    new TeamOnboardingAgent(team, membership, forceOverride)(this)
  }
  object teamAgent {
    def intro(agent: TeamOnboardingAgent)(): Future[Try[Unit]] = FutureHelpers.robustly {
      (for {
        msg <- Some(SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(DescriptionElements(
          SlackEmoji.arrowsCounterclockwise,
          "I started sync'ing your team's public :slack: channels and creating :books: Kifi libraries.",
          agent.team.publicChannelsLastSyncedAt match {
            case Some(lastTime) =>
              DescriptionElements("The last time someone from your team sync'd this was", lastTime, "so it should only take us a few seconds to pull in what's new.")
            case None =>
              DescriptionElements("Give me a few minutes and I'll let you know when it's all set up.")
          }
        )))).filter(_ => agent.isWorking)
      } yield {
        slackClient.sendToSlackViaUser(agent.membership.slackUserId, agent.membership.slackTeamId, agent.membership.slackUserId.asChannel, msg).andThen(logFTUI(agent, msg)).map(_ => ())
      }) getOrElse {
        slackLog.info(s"Decided not to send a FTUI to team ${agent.team.slackTeamName.value}")
        Future.successful(Unit)
      }
    }
    def channels(agent: TeamOnboardingAgent)(membership: SlackTeamMembership, channels: Seq[SlackPublicChannelInfo]): Future[Try[Unit]] = FutureHelpers.robustly {
      import DescriptionElements._
      FutureHelpers.accumulateRobustly(channels) { ch =>
        import SlackSearchRequest._
        val query = Query(Query.in(ch.channelName), Query.hasLink)
        slackClient.searchMessages(membership.token.get, SlackSearchRequest(query)).map { response =>
          response.messages.total
        }
      }.flatMap { msgsByChannel =>
        val numMsgsWithLinks = msgsByChannel.values.toList match {
          case results if results.forall(_.isSuccess) => Some(msgsByChannel.collect { case (_, Success(numMsgs)) => numMsgs }.sum)
          case results =>
            slackLog.error(
              "Failed to predict the number of ingestable links for", membership.slackTeamName.value,
              results.collect { case Failure(fail) => fail.getMessage }.mkString("[", ",", "]")
            )
            None
        }
        (for {
          org <- agent.team.organizationId.map { orgId => db.readOnlyMaster { implicit s => orgRepo.get(orgId) } }
          msg <- Some(SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(
            if (channels.nonEmpty) {
              DescriptionElements.unlines(Seq(
                DescriptionElements("I just sync'd", if (channels.length > 1) s"${channels.length} channels" else "one channel",
                  "and all the :linked_paperclips: links I could find over to Kifi, and boy are my robotic arms tired.",
                  numMsgsWithLinks.map(numMsgs => DescriptionElements(
                    "I",
                    numMsgs match {
                      case n if n > 1 => s"found $n messages with links, and once they're indexed you can access all of them"
                      case 1 => "only found one message with a link, though. Once it's indexed you can access it"
                      case 0 => "couldn't find any messages with links, though. Sorry :(. If we HAD found any, you could find them"
                    },
                    "inside your", if (channels.length > 1) "newly created libraries" else "new library", "."
                  )),
                  "If you have any questions in the mean time, you can email my human friends at support@kifi.com."
                ),
                DescriptionElements(
                  "As soon as your libraries and links are nice and tidy, I'll send a welcome message to your team in #general",
                  "to let them know about what Kifi's Slack integration can do for them.",
                  "As a :robot_face: robot, I pledge to take mission control settings pretty seriously. Take a look at your granular team settings",
                  "here" --> LinkElement(pathCommander.orgIntegrationsPageViaSlack(org, agent.team.slackTeamId)),
                  "and you can turn off any messages I send to your team (and toggle all of your library integrations)."
                )
              ))
            } else {
              DescriptionElements(
                SlackEmoji.sweatSmile, "I just looked but I didn't find any",
                agent.team.publicChannelsLastSyncedAt match {
                  case Some(lastTime) => DescriptionElements("new channels since someone from your team last sync'd", lastTime, ".")
                  case None => DescriptionElements("public channels.")
                },
                "If you think this is an error, please email my human friends at support@kifi.com."
              )
            }
          ))).filter(_ => agent.isWorking)
        } yield {
          agent.dieIf(channels.isEmpty)
          slackClient.sendToSlackViaUser(agent.membership.slackUserId, agent.membership.slackTeamId, agent.membership.slackUserId.asChannel, msg).andThen(logFTUI(agent, msg)).map(_ => ())
        }) getOrElse {
          slackLog.info(s"Decided not to send a FTUI to team ${agent.team.slackTeamName.value}")
          Future.successful(Unit)
        }
      }
    }
    private def logFTUI(agent: TeamOnboardingAgent, msg: SlackMessageRequest): PartialFunction[Try[_], Unit] = {
      case Success(_) => slackLog.info("Pushed a team FTUI to", agent.membership.slackUsername.value, "in", agent.team.slackTeamName.value, "saying", msg.text)
      case Failure(fail) => slackLog.warn("Failed to push team FTUI to", agent.membership.slackUsername.value, "in", agent.team.slackTeamName.value, "because:", fail.getMessage)
    }
  }
}
