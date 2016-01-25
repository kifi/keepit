package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.time._
import com.keepit.common.util.RandomChoice._
import com.keepit.common.util.{ BasicElement, DescriptionElements, LinkElement, Ord }
import com.keepit.model._
import com.keepit.slack.models._
import org.apache.commons.math3.random.MersenneTwister
import org.joda.time.Period

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

@ImplementedBy(classOf[SlackDigestNotifierImpl])
trait SlackDigestNotifier {
  def pushDigestNotificationsForRipeTeams(): Future[Unit]
  def pushDigestNotificationsForRipeChannels(): Future[Unit]
}

object SlackDigestNotifier {
  val minPeriodBetweenTeamDigests = Period.seconds(10)
  val minPeriodBetweenChannelDigests = Period.seconds(10)
  val minIngestedLinksForChannelDigest = 2
  val minIngestedLinksForTeamDigest = 2
  val KifiSlackTeamId = SlackTeamId("T02A81H50")
}

@Singleton
class SlackDigestNotifierImpl @Inject() (
  db: Database,
  slackTeamRepo: SlackTeamRepo,
  slackChannelRepo: SlackChannelRepo,
  channelToLibRepo: SlackChannelToLibraryRepo,
  libToChannelRepo: LibraryToSlackChannelRepo,
  slackClient: SlackClientWrapper,
  pathCommander: PathCommander,
  libRepo: LibraryRepo,
  ktlRepo: KeepToLibraryRepo,
  keepRepo: KeepRepo,
  attributionRepo: KeepSourceAttributionRepo,
  organizationInfoCommander: OrganizationInfoCommander,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  implicit val executionContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends SlackDigestNotifier {
  val slackLog = new SlackLog(InhouseSlackChannel.TEST_RYAN)

  val prng = new MersenneTwister(clock.now.getMillis)

  def pushDigestNotificationsForRipeChannels(): Future[Unit] = {
    val ripeChannelsFut = db.readOnlyReplicaAsync { implicit s =>
      // TODO(ryan): right now this only sends digests to channels in the Kifi slack team, once it works change that
      slackChannelRepo.getRipeForPushingDigestNotification(lastPushOlderThan = clock.now minus SlackDigestNotifier.minPeriodBetweenChannelDigests).filter { ch =>
        val isKifi = ch.slackTeamId == SlackDigestNotifier.KifiSlackTeamId
        val teamHasIntegration = slackTeamRepo.getBySlackTeamId(ch.slackTeamId).isDefined
        isKifi // && !teamHasIntegration // TODO(ryan): switch `isKifi` for `!teamHasIntegration
      }
    }
    for {
      ripeChannels <- ripeChannelsFut
      pushes <- FutureHelpers.accumulateRobustly(ripeChannels)(pushDigestNotificationForChannel)
    } yield Unit
  }

  def pushDigestNotificationsForRipeTeams(): Future[Unit] = {
    val ripeTeamsFut = db.readOnlyReplicaAsync { implicit s =>
      slackTeamRepo.getRipeForPushingDigestNotification(lastPushOlderThan = clock.now minus SlackDigestNotifier.minPeriodBetweenTeamDigests).filter {
        _.slackTeamId == SlackDigestNotifier.KifiSlackTeamId
      }
    }
    for {
      ripeTeams <- ripeTeamsFut
      pushes <- FutureHelpers.accumulateRobustly(ripeTeams)(pushDigestNotificationForTeam)
    } yield Unit
  }

  private def createTeamDigest(slackTeam: SlackTeam)(implicit session: RSession): Option[SlackTeamDigest] = {
    for {
      org <- slackTeam.organizationId.flatMap(organizationInfoCommander.getBasicOrganizationHelper)
      librariesByChannel = {
        val teamIntegrations = channelToLibRepo.getBySlackTeam(slackTeam.slackTeamId).filter(_.isWorking)
        val teamChannelIds = teamIntegrations.flatMap(_.slackChannelId).toSet
        val librariesById = libRepo.getActiveByIds(teamIntegrations.map(_.libraryId).toSet)
        teamIntegrations.groupBy(_.slackChannelId).collect {
          case (Some(channelId), integrations) =>
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
          val newKeepIds = ktlRepo.getByLibrariesAddedSince(libs.map(_.id.get), slackTeam.lastDigestNotificationAt).map(_.keepId).toSet
          val newSlackKeepsById = keepRepo.getByIds(newKeepIds).filter { case (_, keep) => keep.source == KeepSource.slack }
          val ingestedLinks = attributionRepo.getByKeepIds(newSlackKeepsById.keySet).collect {
            case (kId, SlackAttribution(msg)) if msg.channel.id == channelId => newSlackKeepsById.get(kId).map(_.url)
          }.flatten.toSet
          channelId -> ingestedLinks
      }
      digest <- Some(SlackTeamDigest(
        slackTeam = slackTeam,
        timeSinceLastDigest = new Period(slackTeam.lastDigestNotificationAt, clock.now),
        org = org,
        ingestedLinksByChannel = ingestedLinksByChannel,
        librariesByChannel = librariesByChannel
      )).filter(_.numIngestedLinks >= SlackDigestNotifier.minIngestedLinksForTeamDigest)
    } yield digest
  }

  private def kifiHellos(n: Int): IndexedSeq[DescriptionElements] = {
    import DescriptionElements._
    IndexedSeq(
      DescriptionElements("Look at this boatload :rowboat: of links!"),
      DescriptionElements("Your Kifi game is strong :muscle:! Take a look at all these links!"),
      DescriptionElements("Wow! Your team is killing it :skull: lately! Keep up the good work!"),
      DescriptionElements("Surprise! I brought you a gift :gift:! It's all the links your team found this week. I'm bad at keeping secrets"),
      DescriptionElements("Your team captured links this week like it was taking candy :candy: from a baby :baby:. And I'd bet they'd be good at that, too."),
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
      DescriptionElements("All hail the kings and queens of Kifi :crown:! What glorious links you've captured, your highnesses!"),
      DescriptionElements("At this point, I'd say that you've stashed away enough links for the long winter :squirrel:!"),
      DescriptionElements("Your team must've found some kind of Kifi cheat code :video_game: Look at all these links!"),
      DescriptionElements("Your team must love The Legend of Zelda :princess: because you clearly have a serious link obsession."),
      DescriptionElements("You are clearly not the weak :muscle: link on your team when it comes to stashing links!"),
      DescriptionElements("Can I take a selfie :iphone: with you? You're a Kifi celebrity!"),
      DescriptionElements("Your team might wanna cool it on the caffeine :coffee:! I mean, this is a lot of hyperlinks."),
      DescriptionElements("Your team is turning link capturing into a science :microscope:!"),
      DescriptionElements("Your team must run :fuelpump: on links because they can't get enough of ‘em!"),
      DescriptionElements("Your team is practically swimming :swimmer: in links! Looks!"),
      DescriptionElements("If you had a nickel :moneybag: for every link you captured, you'd have", n, "nickels. That's simple math, my friend."),
      DescriptionElements("Your team racked up a baker's dozen :doughnut: links this week. Reward them with donuts."),
      DescriptionElements("Your team captured links this week like it was taking candy :candy: from a baby :baby:. And I'd bet they'd be good at that, too."),
      DescriptionElements("Your team is turning into a link finding factory :factory:!"),
      DescriptionElements("Wow! You've added more links than you can shake a stick at :ice_hockey_stick_and_puck:"),
      DescriptionElements("No need to :fishing_pole_and_fish: for your links.  We've got your team's summary right here."),
      DescriptionElements("Don't worry!  We didn't :maple_leaf: your links behind.  Here they are!"),
      DescriptionElements(":watch: out!  A summary of your team's awesome work is incoming!"),
      DescriptionElements("My good friend Bing Bong :elephant: will never forget your links.  Here they are!")
    ) ++ Some(
        DescriptionElements("Meow :cat: You kept", n, "links, cats have", n, "lives. Coincidence? I think not.")
      ).filter(_ => n == 9)
  }

  private val kifiSlackTipAttachments: IndexedSeq[SlackAttachment] = {
    import DescriptionElements._
    IndexedSeq(
      SlackAttachment(color = Some(LibraryColor.SKY_BLUE.hex), text = Some(DescriptionElements.formatForSlack(DescriptionElements(
        SlackEmoji.magnifyingGlass, "Search them using the `/kifi` Slack command"
      )))).withFullMarkdown,
      SlackAttachment(color = Some(LibraryColor.ORANGE.hex), text = Some(DescriptionElements.formatForSlack(DescriptionElements(
        "See them on Google by installing the", "browser extension" --> LinkElement(PathCommander.browserExtension)
      )))).withFullMarkdown
    )
  }

  private def describeTeamDigest(digest: SlackTeamDigest)(implicit session: RSession): SlackMessageRequest = {
    import DescriptionElements._
    val topLibraries = digest.numIngestedLinksByLibrary.toList.sortBy { case (lib, numLinks) => numLinks }(Ord.descending).take(3).collect { case (lib, numLinks) if numLinks > 0 => lib }
    val text = DescriptionElements.unlines(List(
      prng.choice(kifiHellos(digest.numIngestedLinks)),
      DescriptionElements("We have collected", s"${digest.numIngestedLinks} links" --> LinkElement(pathCommander.orgLibrariesPage(digest.org)),
        "from", digest.slackTeam.slackTeamName.value, withinTheLast(digest.timeSinceLastDigest), SlackEmoji.gear --> LinkElement(PathCommander.settingsPage))
    ))
    val attachments = List(
      SlackAttachment(color = Some(LibraryColor.GREEN.hex), text = Some(DescriptionElements.formatForSlack(DescriptionElements(
        "Your most active", if (topLibraries.length > 1) "libraries are" else "library is",
        DescriptionElements.unwordsPretty(topLibraries.map(lib => DescriptionElements(lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute))))
      )))).withFullMarkdown
    ) ++ prng.choice(kifiSlackTipAttachments)

    SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(text), attachments).quiet
  }
  private def pushDigestNotificationForTeam(team: SlackTeam): Future[Unit] = {
    val now = clock.now
    val msgOpt = db.readOnlyMaster { implicit s => createTeamDigest(team).map(describeTeamDigest) }
    val generalChannelFut = team.generalChannelId match {
      case Some(channelId) => Future.successful(Some(channelId))
      case None => slackClient.getGeneralChannelId(team.slackTeamId)
    }
    generalChannelFut.flatMap { generalChannelOpt =>
      val pushOpt = for {
        msg <- msgOpt
        generalChannel <- generalChannelOpt
      } yield {
        slackClient.sendToSlackTeam(team.slackTeamId, generalChannel, msg).andThen {
          case Success(_: Unit) =>
            db.readWrite { implicit s =>
              slackTeamRepo.save(slackTeamRepo.get(team.id.get).withGeneralChannelId(generalChannel).withLastDigestNotificationAt(now))
            }
            slackLog.info("Pushed a digest to", team.slackTeamName.value)
          case Failure(fail) =>
            slackLog.warn("Failed to push a digest to", team.slackTeamName.value, "because", fail.getMessage)
        }
      }
      pushOpt.getOrElse(Future.successful(Unit))
    }
  }

  private def createChannelDigest(slackChannel: SlackChannel)(implicit session: RSession): Option[SlackChannelDigest] = {
    val ingestions = channelToLibRepo.getBySlackTeamAndChannel(slackChannel.slackTeamId, slackChannel.slackChannelId).filter(_.isWorking)
    val librariesIngestedInto = libRepo.getActiveByIds(ingestions.map(_.libraryId).toSet)
    val ingestedLinks = {
      val newKeepIds = ktlRepo.getByLibrariesAddedSince(librariesIngestedInto.keySet, slackChannel.lastNotificationAt).map(_.keepId).toSet
      val newSlackKeepsById = keepRepo.getByIds(newKeepIds).filter { case (_, keep) => keep.source == KeepSource.slack }
      attributionRepo.getByKeepIds(newSlackKeepsById.keySet).collect {
        case (kId, SlackAttribution(msg)) if msg.channel.id == slackChannel.slackChannelId =>
          newSlackKeepsById.get(kId).map(_.url)
      }.flatten.toSet
    }

    Some(SlackChannelDigest(
      slackChannel = slackChannel,
      timeSinceLastDigest = new Period(slackChannel.lastNotificationAt, clock.now),
      ingestedLinks = ingestedLinks,
      libraries = librariesIngestedInto.values.toList
    )).filter(_.numIngestedLinks >= SlackDigestNotifier.minIngestedLinksForChannelDigest)
  }

  private def describeChannelDigest(digest: SlackChannelDigest)(implicit session: RSession): SlackMessageRequest = {
    import DescriptionElements._
    SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(DescriptionElements.unlines(List(
      DescriptionElements("We have collected", digest.numIngestedLinks, "links from",
        digest.slackChannel.slackChannelName.value, withinTheLast(digest.timeSinceLastDigest)),
      DescriptionElements("You can browse through them in",
        DescriptionElements.unwordsPretty(digest.libraries.map(lib => lib.name --> LinkElement(pathCommander.pathForLibrary(lib)))))
    )))).quiet
  }
  private def pushDigestNotificationForChannel(channel: SlackChannel): Future[Unit] = {
    val now = clock.now
    val msgOpt = db.readOnlyMaster { implicit s => createChannelDigest(channel).map(describeChannelDigest) }
    val pushOpt = for {
      msg <- msgOpt
    } yield {
      slackClient.sendToSlackChannel(channel.slackTeamId, channel.idAndName, msg).andThen {
        case Success(_: Unit) =>
          db.readWrite { implicit s =>
            slackChannelRepo.save(slackChannelRepo.get(channel.id.get).withLastNotificationAtLeast(now))
          }
          slackLog.info("Pushed a digest to", channel.slackChannelName.value, "in team", channel.slackTeamId.value)
        case Failure(fail) =>
          slackLog.warn("Failed to push a digest to", channel.slackChannelName.value, "in team", channel.slackTeamId.value)
      }
    }
    pushOpt.getOrElse(Future.successful(Unit))
  }
}
