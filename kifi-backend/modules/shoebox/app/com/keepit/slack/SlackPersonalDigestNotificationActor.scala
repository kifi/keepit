package com.keepit.slack

import com.google.inject.Inject
import com.keepit.commanders.{ OrganizationInfoCommander, PathCommander }
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.core.{ mapExtensionOps, optionExtensionOps }
import com.keepit.common.crypto.KifiUrlRedirectHelper
import com.keepit.common.strings._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.net.Param
import com.keepit.common.performance.StatsdTiming
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time.{ Clock, _ }
import com.keepit.common.util.RandomChoice._
import com.keepit.common.util.{ DescriptionElements, LinkElement, Ord }
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.social.Author
import com.kifi.juggle._
import org.apache.commons.math3.random.MersenneTwister
import org.joda.time.Duration

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object SlackPersonalDigestConfig {
  val minDelayInsideTeam = Duration.standardMinutes(10)

  val delayAfterSuccessfulDigest = Duration.standardDays(7)
  val delayAfterFailedDigest = Duration.standardDays(1)
  val delayAfterNoDigest = Duration.standardHours(6)
  val maxProcessingDuration = Duration.standardHours(1)
  val minIngestedMessagesForPersonalDigest = 2

  val minDigestConcurrency = 1
  val maxDigestConcurrency = 10

  val superAwesomeWelcomeMessageGIF = "https://d1dwdv9wd966qu.cloudfront.net/img/slack_initial_personal_digest.gif"
}

class SlackPersonalDigestNotificationActor @Inject() (
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
  orgInfoCommander: OrganizationInfoCommander,
  orgExperimentRepo: OrganizationExperimentRepo,
  slackAnalytics: SlackAnalytics,
  val heimdalContextBuilder: HeimdalContextBuilderFactory,
  implicit val ec: ExecutionContext,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends FortyTwoActor(airbrake) with ConcurrentTaskProcessingActor[Id[SlackTeamMembership]] {

  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SLACK)
  import SlackPersonalDigestConfig._

  protected val minConcurrentTasks = minDigestConcurrency
  protected val maxConcurrentTasks = maxDigestConcurrency

  protected def pullTasks(limit: Int): Future[Seq[Id[SlackTeamMembership]]] = {
    val now = clock.now
    db.readWriteAsync { implicit session =>
      val vipTeams = {
        val orgs = orgExperimentRepo.getOrganizationsByExperiment(OrganizationExperimentType.SLACK_PERSONAL_DIGESTS).toSet
        slackTeamRepo.getByOrganizationIds(orgs).values.flatten.map(_.slackTeamId).toSet
      }
      val ripeIds = slackMembershipRepo.getRipeForPersonalDigest(
        limit = limit,
        overrideProcessesOlderThan = now minus maxProcessingDuration,
        now = now,
        vipTeams = vipTeams
      )
      ripeIds.filter(id => slackMembershipRepo.markAsProcessing(id, overrideProcessesOlderThan = now minus maxProcessingDuration))
    }
  }

  protected def processTasks(ids: Seq[Id[SlackTeamMembership]]): Map[Id[SlackTeamMembership], Future[Unit]] = {
    ids.map(id => id -> pushDigestNotificationForUser(id)).toMap
  }

  private def pushDigestNotificationForUser(membershipId: Id[SlackTeamMembership]): Future[Unit] = {
    val now = clock.now
    val (membership, digestOpt) = db.readOnlyMaster { implicit s =>
      val membership = slackMembershipRepo.get(membershipId)
      val digestOpt = createPersonalDigest(membership)
      (membership, digestOpt)
    }
    digestOpt match {
      case None =>
        slackLog.info(s"Considered sending a personal digest to ${membership.slackUsername} in ${membership.slackTeamName} but opted not to")
        db.readWrite { implicit s =>
          slackMembershipRepo.finishProcessing(membershipId, delayAfterNoDigest)
        }
        Future.successful(())
      case Some(digest) =>
        val message = if (membership.lastPersonalDigestAt.isEmpty) messageForFirstTimeDigest(digest) else messageForRegularDigest(digest)
        slackClient.sendToSlackHoweverPossible(membership.slackTeamId, membership.slackUserId.asChannel, message).map(_ => ()).andThen {
          case Success(_) =>
            db.readWrite { implicit s =>
              slackMembershipRepo.updateLastPersonalDigest(membershipId)
              slackTeamRepo.getBySlackTeamId(membership.slackTeamId).foreach { team =>
                slackTeamRepo.save(team.withNoPersonalDigestsUntil(now plus minDelayInsideTeam))
              }
              slackMembershipRepo.finishProcessing(membershipId, delayAfterSuccessfulDigest)
            }
            val contextBuilder = heimdalContextBuilder()
            contextBuilder += ("numChannelMembers", 1)
            contextBuilder += ("slackTeamName", membership.slackTeamName.value)
            slackAnalytics.trackNotificationSent(membership.slackTeamId, membership.slackUserId.asChannel, membership.slackUsername.asChannelName, NotificationCategory.NonUser.PERSONAL_DIGEST, contextBuilder.build)
            slackLog.info("Personal digest to", membership.slackUsername.value, "in team", membership.slackTeamId.value)
          case Failure(fail) =>
            slackLog.warn(s"Failed to push personal digest to ${membership.slackUsername} in ${membership.slackTeamId} because", fail.getMessage)
            db.readWrite { implicit s =>
              slackMembershipRepo.finishProcessing(membershipId, delayAfterFailedDigest)
            }
        }
    }
  }

  @StatsdTiming("SlackPersonalDigestNotificationActor.createPersonalDigest")
  private def createPersonalDigest(membership: SlackTeamMembership)(implicit session: RSession): Option[SlackPersonalDigest] = {
    for {
      slackTeam <- slackTeamRepo.getBySlackTeamId(membership.slackTeamId)
      orgId <- slackTeam.organizationId
      org <- orgInfoCommander.getBasicOrganizationHelper(orgId)
      _ <- Some(true) if (membership.personalDigestSetting match {
        case SlackPersonalDigestSetting.On => true
        case SlackPersonalDigestSetting.Defer => orgConfigRepo.getByOrgId(orgId).settings.settingFor(StaticFeature.SlackPersonalDigestDefault).safely.contains(StaticFeatureSetting.ENABLED)
        case _ => false
      })
      digest = SlackPersonalDigest(
        slackMembership = membership,
        slackTeam = slackTeam,
        digestPeriod = new Duration(membership.unnotifiedSince, clock.now),
        org = org,
        ingestedMessagesByChannel = getIngestedMessagesForSlackUser(membership)
      )
      relevantDigest <- Some(digest).filter(_.numIngestedMessages >= minIngestedMessagesForPersonalDigest)
    } yield relevantDigest
  }

  private def getIngestedMessagesForSlackUser(membership: SlackTeamMembership)(implicit session: RSession): Map[SlackChannelIdAndPrettyName, Seq[(Keep, PrettySlackMessage)]] = {
    // I'm so sorry that this function exists
    // This grabs the (keep, message) pairs that are a result of ingestion that:
    //     1. Are from this slack user
    //     2. Are marked as having been sent (in Slack) AFTER at least one of the ingestions in that channel was created
    //     3. Were ingested (and thus turned into a keep) since the last time we sent a personal digest to this user (if ever)
    val keepsForThisMembership = attributionRepo.getKeepIdsByAuthor(Author.SlackUser(membership.slackTeamId, membership.slackUserId))
    val attributions = attributionRepo.getByKeepIds(keepsForThisMembership).collect {
      case (keepId, slack: SlackAttribution) => keepId -> slack
    }
    val keepsById = keepRepo.getByIds(keepsForThisMembership)
    val attributionsByChannel = attributions.groupBy(_._2.message.channel)
    val oldestIntegrationByChannel = channelToLibRepo.getBySlackTeam(membership.slackTeamId).groupBy(_.slackChannelId).mapValuesStrict { integrations =>
      integrations.map(_.createdAt).min
    }
    attributionsByChannel.flatMap {
      case (channel, attrsAndKeeps) => oldestIntegrationByChannel.get(channel.id).map { baseTimestamp =>
        channel -> attrsAndKeeps.flatMap {
          case (kId, attr) => keepsById.get(kId).map(_ -> attr.message).filter {
            case (k, msg) => msg.timestamp.toDateTime.isAfter(baseTimestamp) && !membership.lastPersonalDigestAt.exists(lastTime => lastTime isAfter k.createdAt)
          }
        }.toSeq
      }
    }
  }

  // "Pure" functions
  private def messageForFirstTimeDigest(digest: SlackPersonalDigest): SlackMessageRequest = {
    import DescriptionElements._
    val slackTeamId = digest.slackMembership.slackTeamId
    def trackingParams(subaction: String) = SlackAnalytics.generateTrackingParams(digest.slackMembership.slackUserId.asChannel, NotificationCategory.NonUser.PERSONAL_DIGEST, Some(subaction))
    val (mostRecentKeep, mostRecentIngestedMsg) = digest.ingestedMessagesByChannel.values.flatten.maxBy { case (kId, msg) => msg.timestamp }
    val linkToMostRecentKeep = LinkElement(pathCommander.keepPageOnKifiViaSlack(mostRecentKeep, slackTeamId).withQuery(trackingParams("latestMessage")))
    val linkToSquelch = LinkElement(pathCommander.slackPersonalDigestToggle(slackTeamId, digest.slackMembership.slackUserId, turnOn = false).withQuery(trackingParams("turnOff")))
    val text = DescriptionElements.unlines(Seq(
      DescriptionElements(
        SlackEmoji.wave, s"Hey! Kifibot here, just letting you know that your team set up a Kifi integration so I've saved of couple of links you shared.",
        "I also scanned the text on those pages so you can search for them more easily."
      ),
      DescriptionElements(
        "Join", "your team on Kifi" --> LinkElement(pathCommander.orgPageViaSlack(digest.org, slackTeamId).withQuery(trackingParams("org"))), "to get:"
      )
    ))
    val attachments = List(
      SlackAttachment(color = None, text = Some(DescriptionElements.formatForSlack(DescriptionElements(
        SlackEmoji.books, "Access to your archived links", "-",
        "For example, here's the archive of your recent message",
        mostRecentIngestedMsg.channel.name.map(chName => DescriptionElements("in", s"#${chName.value}:")),
        mostRecentKeep.title.getOrElse(mostRecentKeep.url).abbreviate(60) --> linkToMostRecentKeep
      )))),
      SlackAttachment(color = None, text = Some(DescriptionElements.formatForSlack(DescriptionElements(
        SlackEmoji.magnifyingGlass, "Google search integration", "-",
        "See the pages your coworkers are shared on top of Google search results"
      )))).withImageUrl(superAwesomeWelcomeMessageGIF),
      SlackAttachment(color = None, text = Some(DescriptionElements.formatForSlack(DescriptionElements(
        SlackEmoji.robotFace,
        "Also, my binary code is a mess right now, so while I'm in the midst of spring cleaning I won't be responding to any messages you send my way  :zipper_mouth_face:.",
        "You can still", "opt to stop receiving notifications" --> linkToSquelch,
        "or if you've got questions email my human friends at support@kifi.com."
      ))))
    )
    SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(text), attachments).quiet
  }
  private def messageForRegularDigest(digest: SlackPersonalDigest): SlackMessageRequest = {
    import DescriptionElements._
    def trackingParams(subaction: String) = SlackAnalytics.generateTrackingParams(digest.slackMembership.slackUserId.asChannel, NotificationCategory.NonUser.PERSONAL_DIGEST, Some(subaction))
    val linkToFeed = LinkElement(pathCommander.ownKeepsFeedPageViaSlack(digest.slackMembership.slackTeamId).withQuery(trackingParams("ownFeed")))
    val linkToUnsubscribe = LinkElement(pathCommander.slackPersonalDigestToggle(digest.slackMembership.slackTeamId, digest.slackMembership.slackUserId, turnOn = false).withQuery(trackingParams("turnOff")))
    val text = DescriptionElements.unlines(List(
      prng.choice(puns),
      DescriptionElements("You've sent", s"${digest.numIngestedMessages} links" --> linkToFeed, inTheLast(digest.digestPeriod), ".",
        "I archived them for you, and indexed the pages so you can search for them more easily."),
      DescriptionElements("If you don't want to get any more of these notifications,", "click here" --> linkToUnsubscribe)
    ))
    SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(text))
  }

  private val prng = new MersenneTwister(System.currentTimeMillis())
  private val puns = IndexedSeq[DescriptionElements](
    DescriptionElements("Look at this boatload :rowboat: of links!"),
    DescriptionElements("Surprise! I brought you a gift :gift:! It's all the links you messaged to your team this week. I'm bad at keeping secrets"),
    DescriptionElements("You're turning into a link finding factory :factory:!"),
    DescriptionElements("Man, you really hit the links :golf: hard this week! See what I mean?!"),
    DescriptionElements("You're making it rain :umbrella:! Check out all these links!"),
    DescriptionElements("Since you stashed so many links, I think you should watch cat :cat: videos the rest of the day. Go ahead, you earned it."),
    DescriptionElements("You must love The Legend of Zelda :princess: because this link obsession is obvi."),
    DescriptionElements("You might wanna cool it on the caffeine :coffee:! I mean, this is a lot of hyperlinks."),
    DescriptionElements("You're turning link capturing into a science :microscope:!"),
    DescriptionElements("You racked up a baker's dozen :doughnut: links this week. Reward yourself with donut!"),
    DescriptionElements("Wow! You've added more links than you can shake a stick at :ice_hockey_stick_and_puck:"),
    DescriptionElements("No need to :fishing_pole_and_fish: for your links.  We've got your summary right here."),
    DescriptionElements("Don't worry!  We didn't :maple_leaf: your links behind.  Here they are!"),
    DescriptionElements("You've gotta be :cat2: kitten me, right meow.  Did you really save all those links?!")
  )
}
