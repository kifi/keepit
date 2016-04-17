package com.keepit.slack

import com.google.inject.Inject
import com.keepit.commanders.PathCommander
import com.keepit.commanders.gen.BasicOrganizationGen
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core.futureExtensionOps
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time.{ Clock, _ }
import com.keepit.common.util.RandomChoice._
import com.keepit.common.util.{ DescriptionElements, LinkElement, Ord }
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.slack.models._
import com.kifi.juggle._
import org.apache.commons.math3.random.MersenneTwister
import org.joda.time.Duration

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

object SlackTeamDigestConfig {
  val initialDelayForWelcomeMessage = Duration.standardHours(2)
  val minPeriodBetweenTeamDigests = Duration.standardDays(14)
  val minIngestedLinksForTeamDigest = 10
}

class SlackTeamDigestNotificationActor @Inject() (
  db: Database,
  channelToLibRepo: SlackChannelToLibraryRepo,
  slackTeamRepo: SlackTeamRepo,
  slackMembershipRepo: SlackTeamMembershipRepo,
  orgConfigRepo: OrganizationConfigurationRepo,
  libRepo: LibraryRepo,
  attributionRepo: KeepSourceAttributionRepo,
  ktlRepo: KeepToLibraryRepo,
  keepRepo: KeepRepo,
  basicUserRepo: BasicUserRepo,
  pathCommander: PathCommander,
  slackClient: SlackClientWrapper,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  basicOrganizationGen: BasicOrganizationGen,
  slackAnalytics: SlackAnalytics,
  val heimdalContextBuilder: HeimdalContextBuilderFactory,
  implicit val ec: ExecutionContext,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends FortyTwoActor(airbrake) with ConcurrentTaskProcessingActor[Set[Id[SlackTeam]]] {

  protected val minConcurrentTasks = 0
  protected val maxConcurrentTasks = 1

  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SLACK)
  import SlackTeamDigestConfig._

  val prng = new MersenneTwister(clock.now.getMillis)

  type Task = Set[Id[SlackTeam]]
  protected def pullTasks(limit: Int): Future[Seq[Task]] = {
    if (limit == 1) pullTask().map(Seq(_))
    else Future.successful(Seq.empty)
  }

  protected def processTasks(tasks: Seq[Task]): Map[Task, Future[Unit]] = {
    tasks.map { task => task -> processTask(task).imap(_ => ()) }.toMap
  }

  private def pullTask(): Future[Set[Id[SlackTeam]]] = {
    val now = clock.now
    db.readOnlyReplicaAsync { implicit session =>
      val ripeIds = slackTeamRepo.getRipeForPushingDigestNotification(now minus minPeriodBetweenTeamDigests).toSet
      val teams = slackTeamRepo.getByIds(ripeIds).values
      val orgIds = teams.flatMap(_.organizationId).toSet
      val orgConfigById = orgConfigRepo.getByOrgIds(orgIds)
      def canSendDigestTo(team: SlackTeam) = {
        val teamHasDigestsEnabled = team.organizationId.flatMap(orgConfigById.get).exists { config =>
          config.settings.settingFor(StaticFeature.SlackTeamDigest).contains(StaticFeatureSetting.ENABLED)
        }
        val teamHasValidTokens = slackMembershipRepo.getBySlackTeam(team.slackTeamId).flatMap(_.tokenWithScopes).exists(_.scopes.contains(SlackAuthScope.ChatWriteBot))
        val creatorHadTimeToTurnUsOff = team.lastDigestNotificationAt.isDefined || team.publicChannelsLastSyncedAt.exists(_ isBefore now.minus(initialDelayForWelcomeMessage))
        teamHasDigestsEnabled && teamHasValidTokens && creatorHadTimeToTurnUsOff
      }
      teams.filter(canSendDigestTo).map(_.id.get).toSet
    }
  }

  private def processTask(ids: Set[Id[SlackTeam]]): Future[Map[SlackTeam, Try[Unit]]] = {
    val result = for {
      teams <- db.readOnlyReplicaAsync { implicit s => slackTeamRepo.getByIds(ids.toSet).values }
      pushes <- FutureHelpers.accumulateRobustly(teams)(pushDigestNotificationForTeam)
    } yield pushes

    result.andThen {
      case Success(pushesByTeam) =>
        slackLog.warn(DescriptionElements.unlines(pushesByTeam.collect {
          case (team, Failure(fail)) => DescriptionElements("Failed to push digest to", team.slackTeamName.value, "(", team.slackTeamId.value, ")", "because", fail.getMessage)
        }.toSeq))
      case Failure(fail) => airbrake.notify("Somehow accumulateRobustly failed entirely?!?", fail)
    }
  }

  private def createMessage(slackTeam: SlackTeam)(implicit session: RSession, slackChannelId: SlackChannelId): Option[SlackMessageRequest] = {
    if (slackTeam.lastDigestNotificationAt.isEmpty) createTeamIntroMessage(slackTeam)
    else createTeamDigest(slackTeam).map(describeTeamDigest)
  }
  private def createTeamIntroMessage(slackTeam: SlackTeam)(implicit session: RSession, slackChannelId: SlackChannelId): Option[SlackMessageRequest] = {
    import DescriptionElements._
    val trackingParams = SlackAnalytics.generateTrackingParams(slackChannelId, NotificationCategory.NonUser.INTEGRATION_WELCOME)
    for {
      ownerId <- slackMembershipRepo.getBySlackTeam(slackTeam.slackTeamId).filter(_.userId.isDefined).minBy(_.createdAt).userId // TODO(ryan): this might be stupid...
      owner <- basicUserRepo.loadActive(ownerId)
      orgId <- slackTeam.organizationId
      org <- basicOrganizationGen.getBasicOrganizationHelper(orgId)
    } yield {
      val txt = DescriptionElements.formatForSlack(DescriptionElements(
        owner.firstName --> LinkElement(pathCommander.welcomePageViaSlack(owner, slackTeam.slackTeamId).withQuery(trackingParams)),
        "connected", slackTeam.slackTeamName.value, "with", s"${org.name} on Kifi" --> LinkElement(pathCommander.orgPageViaSlack(org, slackTeam.slackTeamId).withQuery(trackingParams)),
        "to auto-magically manage links", SlackEmoji.fireworks
      ))
      val attachments = List(
        SlackAttachment(color = Some("#7DBB70"), text = Some(DescriptionElements.formatForSlack(DescriptionElements.unlines(List(
          DescriptionElements(
            SlackEmoji.clipboard, s"Get on", "Kifi" --> LinkElement(pathCommander.browserExtensionViaSlack(slackTeam.slackTeamId).withQuery(trackingParams)),
            "to access your automatically organized lists of links."
          ),
          DescriptionElements("Libraries have been created for each of your public channels. Join Kifi to access your archived links from Slack")
        ))))).withFullMarkdown,
        SlackAttachment(color = Some("#FBF28D"), text = Some(DescriptionElements.formatForSlack(DescriptionElements.unlines(List(
          DescriptionElements(SlackEmoji.star, s"Automatically save links from your #channels"),
          DescriptionElements("Every time someone includes a link in a chat message, we'll save it and capture every word on the page. It'll be automatically archived and searchable.")
        ))))).withFullMarkdown,
        SlackAttachment(color = Some("#C15B81"), text = Some(DescriptionElements.formatForSlack(DescriptionElements.unlines(List(
          DescriptionElements(SlackEmoji.magnifyingGlass, "Searching: Find your links in Slack and Google"),
          DescriptionElements(
            "Everyone can use the slash command `[/kifi <search term>]` to search on Slack. Install our Chrome and Firefox extensions to",
            "get keeps in your Google Search results" --> LinkElement(pathCommander.browserExtensionViaSlack(slackTeam.slackTeamId).withQuery(trackingParams)), "."
          )
        ))))).withFullMarkdown
      )
      SlackMessageRequest.fromKifi(text = txt, attachments).quiet
    }
  }
  private def createTeamDigest(slackTeam: SlackTeam)(implicit session: RSession): Option[SlackTeamDigest] = {
    for {
      org <- slackTeam.organizationId.flatMap(basicOrganizationGen.getBasicOrganizationHelper)
      librariesByChannel = {
        val teamIntegrations = channelToLibRepo.getBySlackTeam(slackTeam.slackTeamId).filter(_.isWorking)
        val librariesById = libRepo.getActiveByIds(teamIntegrations.map(_.libraryId).toSet)
        teamIntegrations.groupBy(_.slackChannelId).map {
          case (channelId, integrations) =>
            channelId -> integrations.flatMap(sctl => librariesById.get(sctl.libraryId)).filter { lib =>
              (lib.visibility, lib.organizationId) match {
                case (LibraryVisibility.PUBLISHED, _) => true
                case (LibraryVisibility.ORGANIZATION, Some(orgId)) if slackTeam.organizationId.contains(orgId) => true
                case _ => false
              }
            }.toSet
        }
      }
      ingestedLinksByChannel = librariesByChannel.map {
        case (channelId, libs) =>
          val newKeepIds = ktlRepo.getByLibrariesAddedSince(libs.map(_.id.get), slackTeam.unnotifiedSince).map(_.keepId).toSet
          val newSlackKeepsById = keepRepo.getActiveByIds(newKeepIds).filter { case (_, keep) => keep.source == KeepSource.slack }
          val ingestedLinks = attributionRepo.getByKeepIds(newSlackKeepsById.keySet).collect {
            case (kId, SlackAttribution(msg, teamId)) if teamId == slackTeam.slackTeamId && msg.channel.id == channelId =>
              newSlackKeepsById.get(kId).map(_.url)
          }.flatten.toSet
          channelId -> ingestedLinks
      }
      digest <- Some(SlackTeamDigest(
        slackTeam = slackTeam,
        digestPeriod = new Duration(slackTeam.unnotifiedSince, clock.now),
        org = org,
        ingestedLinksByChannel = ingestedLinksByChannel,
        librariesByChannel = librariesByChannel
      )).filter(_.numIngestedLinks >= minIngestedLinksForTeamDigest)
    } yield digest
  }

  private def kifiHellos(n: Int): IndexedSeq[DescriptionElements] = {
    import DescriptionElements._
    IndexedSeq(
      DescriptionElements("Look at this boatload :rowboat: of links!"),
      DescriptionElements("Your Kifi game is strong :muscle:! Take a look at all these links!"),
      DescriptionElements("Wow! Your team is killing it :skull: lately! Keep up the good work!"),
      DescriptionElements("Surprise! I brought you a gift :gift:! It's all the links your team found this week. I'm bad at keeping secrets"),
      DescriptionElements("Your team is turning into a link finding factory :factory:!"),
      DescriptionElements("Your Kifi game is on point :point_up:! Look at this boatload of links!"),
      DescriptionElements("Man, your team really hit the links :golf: hard this week! See what I mean?!"),
      DescriptionElements("Give a man a fish and he'll eat for a day. Teach your team to fish :fishing_pole_and_fish: for links and they'll...I have no idea what I'm talking about."),
      DescriptionElements("Your Kifi game is on fire :fire:! Look at all the links you cooked up!"),
      DescriptionElements("Your team is making it rain :umbrella:! Check out all these links!"),
      DescriptionElements("Christmas :santa:  is coming early for you! Your stocking is stuffed with links!"),
      DescriptionElements("I see a ton of links in your future :crystal_ball:!"),
      DescriptionElements("Today's your lucky :four_leaf_clover: day! Look at all these links!"),
      DescriptionElements("Since you stashed so many links, I think you should watch cat :cat: videos the rest of the day. Go ahead, you earned it."),
      DescriptionElements("At this point, I'd say that you've stashed away enough links for the long winter :squirrel:!"),
      DescriptionElements("Your team must've found some kind of Kifi cheat code :video_game: Look at all these links!"),
      DescriptionElements("Your team must love The Legend of Zelda :princess: because you clearly have a serious link obsession."),
      DescriptionElements("Your team might wanna cool it on the caffeine :coffee:! I mean, this is a lot of hyperlinks."),
      DescriptionElements("Your team is turning link capturing into a science :microscope:!"),
      DescriptionElements("Your team must run :fuelpump: on links because they can't get enough of ‘em!"),
      DescriptionElements("Your team is practically swimming :swimmer: in links! Looks!"),
      DescriptionElements("If you had a nickel :moneybag: for every link you captured, you'd have", n, "nickels. That's simple math, my friend."),
      DescriptionElements("Your team racked up a baker's dozen :doughnut: links this week. Reward them with donuts."),
      DescriptionElements("Your team is turning into a link finding factory :factory:!"),
      DescriptionElements("Wow! You've added more links than you can shake a stick at :ice_hockey_stick_and_puck:"),
      DescriptionElements("No need to :fishing_pole_and_fish: for your links.  We've got your team's summary right here."),
      DescriptionElements("Don't worry!  We didn't :maple_leaf: your links behind.  Here they are!"),
      DescriptionElements("My good friend Bing Bong :elephant: will never forget your links.  Here they are!"),
      DescriptionElements("You've gotta be :cat2: kitten me right meow! You've been up to some serious link saving.")
    )
  }

  private def kifiSlackTipAttachments(slackTeamId: SlackTeamId)(implicit slackChannelId: SlackChannelId): IndexedSeq[SlackAttachment] = {
    import DescriptionElements._
    val trackingParams = SlackAnalytics.generateTrackingParams(slackChannelId, NotificationCategory.NonUser.INTEGRATION_WELCOME)
    IndexedSeq(
      SlackAttachment(color = Some(LibraryColor.SKY_BLUE.hex), text = Some(DescriptionElements.formatForSlack(DescriptionElements(
        SlackEmoji.magnifyingGlass, "Search them using the `/kifi` Slack command"
      )))).withFullMarkdown,
      SlackAttachment(color = Some(LibraryColor.ORANGE.hex), text = Some(DescriptionElements.formatForSlack(DescriptionElements(
        "See them on Google by installing the", "browser extension" --> LinkElement(pathCommander.browserExtensionViaSlack(slackTeamId).withQuery(trackingParams))
      )))).withFullMarkdown
    )
  }

  private def describeTeamDigest(digest: SlackTeamDigest)(implicit session: RSession, slackChannelId: SlackChannelId): SlackMessageRequest = {
    import DescriptionElements._
    val slackTeamId = digest.slackTeam.slackTeamId
    val trackingParams = SlackAnalytics.generateTrackingParams(slackChannelId, NotificationCategory.NonUser.TEAM_DIGEST)
    val topLibraries = digest.numIngestedLinksByLibrary.toList.sortBy { case (lib, numLinks) => numLinks }(Ord.descending).take(3).collect { case (lib, numLinks) if numLinks > 0 => lib }
    val text = DescriptionElements.unlines(List(
      prng.choice(kifiHellos(digest.numIngestedLinks)),
      DescriptionElements("We have collected", s"${digest.numIngestedLinks} links" --> LinkElement(pathCommander.orgPageViaSlack(digest.org, slackTeamId).withQuery(trackingParams)),
        "from", digest.slackTeam.slackTeamName.value, inTheLast(digest.digestPeriod),
        SlackEmoji.gear, "Edit", "settings" --> LinkElement(pathCommander.orgIntegrationsPageViaSlack(digest.org, slackTeamId).withQuery(trackingParams)), ".")
    ))
    val attachments = List(
      SlackAttachment(color = Some(LibraryColor.GREEN.hex), text = Some(DescriptionElements.formatForSlack(DescriptionElements(
        "Your most active", if (topLibraries.length > 1) "libraries are" else "library is",
        DescriptionElements.unwordsPretty {
          topLibraries.map(lib => DescriptionElements(lib.name --> LinkElement(pathCommander.libraryPageViaSlack(lib, digest.slackTeam.slackTeamId).withQuery(trackingParams))))
        }
      )))).withFullMarkdown
    ) ++ prng.choiceOption(kifiSlackTipAttachments(digest.slackTeam.slackTeamId))

    SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(text), attachments).quiet
  }
  private def pushDigestNotificationForTeam(team: SlackTeam): Future[Unit] = {
    val now = clock.now
    val generalChannelFut = team.generalChannelId match {
      case Some(channelId) => Future.successful(Some(channelId))
      case None => slackClient.getGeneralChannelId(team.slackTeamId)
    }
    generalChannelFut.flatMap { generalChannelOpt =>
      val pushOpt = for {
        generalChannel <- generalChannelOpt
      } yield {
        val msgOpt = db.readOnlyMaster { implicit s => createMessage(team)(s, generalChannel) }
        msgOpt.map { msg =>
          slackClient.sendToSlackHoweverPossible(team.slackTeamId, generalChannel, msg).map { sent =>
            slackLog.info("Team digest to", team.slackTeamName.value, "(", team.slackTeamId.value, ")")
            val contextBuilder = heimdalContextBuilder()
            contextBuilder += ("slackTeamName", team.slackTeamName.value)
            slackAnalytics.trackNotificationSent(team.slackTeamId, generalChannel, Some(SlackChannelName("general")), NotificationCategory.NonUser.TEAM_DIGEST)
            ()
          }
        }.getOrElse(Future.failed(new Exception))
      }.recover {
        case SlackFail.NoValidPushMethod =>
          slackLog.info("Could not find a way to push a digest to", team.slackTeamName.value, "(", team.slackTeamId.value, "), but marking it anwaysy")
          ()
      }.andThen {
        case Success(_) =>
          db.readWrite { implicit s =>
            slackTeamRepo.save(slackTeamRepo.get(team.id.get).withGeneralChannelId(generalChannel).withLastDigestNotificationAt(now))
          }
      }
      pushOpt.getOrElse { Future.successful(()) }
    }
  }
}
