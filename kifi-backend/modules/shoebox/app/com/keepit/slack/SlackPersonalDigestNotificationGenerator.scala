package com.keepit.slack

import com.google.inject.Inject
import com.keepit.commanders.gen.BasicOrganizationGen
import com.keepit.commanders.{ OrganizationInfoCommander, PathCommander }
import com.keepit.common.core.{ mapExtensionOps, optionExtensionOps }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.performance.StatsdTiming
import com.keepit.common.strings._
import com.keepit.common.time._
import com.keepit.common.util.RandomChoice._
import com.keepit.common.util.{ SpecialCharacters, RightBias, DescriptionElements, LinkElement }
import com.keepit.common.util.RightBias._
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.social.Author
import org.apache.commons.math3.random.MersenneTwister
import org.joda.time.{ Minutes, Duration }

import scala.concurrent.ExecutionContext

class SlackPersonalDigestNotificationGenerator @Inject() (
  db: Database,
  channelToLibRepo: SlackChannelToLibraryRepo,
  slackTeamRepo: SlackTeamRepo,
  slackMembershipRepo: SlackTeamMembershipRepo,
  orgConfigRepo: OrganizationConfigurationRepo,
  attributionRepo: KeepSourceAttributionRepo,
  keepRepo: KeepRepo,
  pathCommander: PathCommander,
  clock: Clock,
  basicOrganizationGen: BasicOrganizationGen,
  val heimdalContextBuilder: HeimdalContextBuilderFactory,
  implicit val ec: ExecutionContext,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends Logging {
  import SlackPersonalDigestConfig._

  private final case class OptionOrString[T](x: Either[String, T])

  @StatsdTiming("SlackPersonalDigestNotificationActor.createPersonalDigest")
  def createPersonalDigest(membership: SlackTeamMembership)(implicit session: RSession): RightBias[String, SlackPersonalDigest] = {
    val now = clock.now
    for {
      slackTeam <- slackTeamRepo.getBySlackTeamId(membership.slackTeamId).withLeft("no slack team")
      orgId <- slackTeam.organizationId.withLeft("no org id on slack team")
      org <- basicOrganizationGen.getBasicOrganizationHelper(orgId).withLeft(s"no basic org for $orgId")
      _ <- RightBias.unit.filter(_ => membership.personalDigestSetting match {
        case SlackPersonalDigestSetting.On => true
        case SlackPersonalDigestSetting.Defer => orgConfigRepo.getByOrgId(orgId).settings.settingFor(StaticFeature.SlackPersonalDigestDefault).safely.contains(StaticFeatureSetting.ENABLED)
        case _ => false
      }, "digests not enabled")
      digest = SlackPersonalDigest(
        slackMembership = membership,
        slackTeam = slackTeam,
        allMembers = slackMembershipRepo.getBySlackTeam(membership.slackTeamId),
        digestPeriod = new Duration(membership.unnotifiedSince, now),
        org = org,
        ingestedMessagesByChannel = getIngestedMessagesForSlackUser(membership)
      )
      _ <- RightBias.unit.filter(_ => digest.numIngestedMessages >= minIngestedMessagesForPersonalDigest, s"only ${digest.numIngestedMessages} ingested messages since ${membership.unnotifiedSince}")
      _ <- RightBias.unit.filter(_ =>
        membership.lastPersonalDigestAt.isDefined || digest.mostRecentMessage._2.timestamp.toDateTime.isAfter(now minus maxDelayFromMessageToInitialDigest),
        s"this is the first digest and the most recent message is ${Minutes.minutesBetween(digest.mostRecentMessage._2.timestamp.toDateTime, now).getMinutes} minutes old"
      )
    } yield digest
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
    val keepsById = keepRepo.getActiveByIds(keepsForThisMembership)
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
  def messageForFirstTimeDigest(digest: SlackPersonalDigest): SlackMessageRequest = {
    require(digest.slackMembership.userId.isEmpty, "First time digest is only for non-kifi users")
    import DescriptionElements._
    val slackTeamId = digest.slackMembership.slackTeamId
    def trackingParams(subaction: String) = SlackAnalytics.generateTrackingParams(digest.slackMembership.slackUserId.asChannel, NotificationCategory.NonUser.PERSONAL_DIGEST, Some(subaction))
    val (mostRecentKeep, mostRecentIngestedMsg) = digest.mostRecentMessage
    val linkToOrg = LinkElement(pathCommander.orgPageViaSlack(digest.org, slackTeamId).withQuery(trackingParams("org")))
    val linkToMostRecentKeep = LinkElement(pathCommander.keepPageOnKifiViaSlack(mostRecentKeep, slackTeamId).withQuery(trackingParams("latestMessage")))
    val linkToSquelch = LinkElement(pathCommander.slackPersonalDigestToggle(slackTeamId, digest.slackMembership.slackUserId, turnOn = false).withQuery(trackingParams("turnOff")))
    val numMembersOnKifi = digest.allMembers.count(stm => stm.userId.isDefined)
    val text = DescriptionElements.unlines(Seq(
      DescriptionElements(
        "The link you recently sent",
        mostRecentIngestedMsg.channel.name.map(chName => s"in #${chName.value}" --> LinkElement(mostRecentIngestedMsg.permalink)),
        "was saved in", s"${digest.org.name}'s" --> linkToOrg, "repository on Kifi (a featured app on Slack)."
      ), DescriptionElements(
        "Get your team's links on top of Google Search results w/ the", "browser add-on" --> LinkElement(PathCommander.browserExtension), "."
      ), DescriptionElements(
        "Click", "here" --> linkToSquelch, "to opt out of these notifications."
      )))
    SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(text)).quiet
  }
  def messageForRegularDigest(digest: SlackPersonalDigest): SlackMessageRequest = {
    import DescriptionElements._
    def trackingParams(subaction: String) = SlackAnalytics.generateTrackingParams(digest.slackMembership.slackUserId.asChannel, NotificationCategory.NonUser.PERSONAL_DIGEST, Some(subaction))
    val linkToFeed = LinkElement(pathCommander.ownKeepsFeedPageViaSlack(digest.slackMembership.slackTeamId).withQuery(trackingParams("ownFeed")))
    val linkToOrg = LinkElement(pathCommander.orgPageViaSlack(digest.org, digest.slackTeam.slackTeamId).withQuery(trackingParams("orgPage")))
    val linkToUnsubscribe = LinkElement(pathCommander.slackPersonalDigestToggle(digest.slackMembership.slackTeamId, digest.slackMembership.slackUserId, turnOn = false).withQuery(trackingParams("turnOff")))
    val text = prng.choice(puns)

    val mostRecentKeep = digest.mostRecentMessage._1
    val linkToMostRecentKeep = LinkElement(pathCommander.keepPageOnKifiViaSlack(mostRecentKeep, digest.slackTeam.slackTeamId).withQuery(trackingParams("latestMessage")))
    val attachments = Seq(
      SlackAttachment.simple(DescriptionElements(
        "You've sent", s"${digest.numIngestedMessages} links" --> linkToFeed, inTheLast(digest.digestPeriod), ".",
        "Here's your latest:"
      )),
      SlackAttachment.simple(DescriptionElements(mostRecentKeep.title.getOrElse(mostRecentKeep.url).abbreviate(60) --> linkToMostRecentKeep)),
      SlackAttachment.simple(DescriptionElements(
        "Access all links from", "your team on Kifi" --> linkToOrg, ".",
        "Also, you can", "opt to stop receiving notifications" --> linkToUnsubscribe, "."
      )))
    SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(text), attachments = attachments)
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
