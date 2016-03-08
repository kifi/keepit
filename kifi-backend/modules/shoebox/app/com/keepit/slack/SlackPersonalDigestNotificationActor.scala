package com.keepit.slack

import com.google.inject.Inject
import com.keepit.commanders.{ OrganizationInfoCommander, PathCommander }
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.core.{ mapExtensionOps, optionExtensionOps }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.performance.StatsdTiming
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time.{ Clock, _ }
import com.keepit.common.util.{ DescriptionElements, LinkElement }
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.social.Author
import com.kifi.juggle._
import org.joda.time.Duration

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object SlackPersonalDigestConfig {
  val minDelayInsideTeam = Duration.standardMinutes(10)

  val delayAfterSuccessfulDigest = Duration.standardDays(3)
  val delayAfterFailedDigest = Duration.standardDays(1)
  val delayAfterNoDigest = Duration.standardDays(3)
  val maxProcessingDuration = Duration.standardHours(1)
  val minIngestedMessagesForPersonalDigest = 2

  val minDigestConcurrency = 1
  val maxDigestConcurrency = 10
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
      numIngestedMessagesByChannel = {
        val keepsForThisMembership = attributionRepo.getKeepIdsByAuthor(Author.SlackUser(membership.slackTeamId, membership.slackUserId))
        val messages = attributionRepo.getByKeepIds(keepsForThisMembership).values.collect {
          case SlackAttribution(msg, _) => msg
        }
        val messagesByChannel = messages.groupBy(_.channel)
        val oldestIntegrationByChannel = channelToLibRepo.getBySlackTeam(membership.slackTeamId).groupBy(_.slackChannelId).mapValuesStrict { integrations =>
          integrations.map(_.createdAt).min
        }
        messagesByChannel.flatMap {
          case (channel, msgs) =>
            oldestIntegrationByChannel.get(channel.id).map { baseTimestamp => channel -> messages.count(_.timestamp.toDateTime isAfter baseTimestamp) }
        }
      }
      digest = SlackPersonalDigest(
        slackMembership = membership,
        digestPeriod = new Duration(membership.unnotifiedSince, clock.now),
        org = org,
        numIngestedMessagesByChannel = numIngestedMessagesByChannel
      )
      relevantDigest <- Some(digest).filter(_.numIngestedMessages >= minIngestedMessagesForPersonalDigest)
    } yield relevantDigest
  }

  // "Pure" functions
  private def messageForFirstTimeDigest(digest: SlackPersonalDigest): SlackMessageRequest = {
    import DescriptionElements._
    val slackTeamId = digest.slackMembership.slackTeamId
    val linkToSquelch = LinkElement(pathCommander.slackPersonalDigestToggle(slackTeamId, digest.slackMembership.slackUserId, turnOn = false))
    val text = DescriptionElements(
      SlackEmoji.wave,
      "Hey! Kifibot here, just letting you know that your team set up a Kifi integration so I saved of couple of links you shared.",
      "I also scanned the text on those pages so you can search for them more easily.",
      "Join", "your team on Kifi" --> LinkElement(pathCommander.orgPageViaSlack(digest.org, slackTeamId)), "to get:"
    )
    val attachments = List(
      SlackAttachment(color = None, text = Some(DescriptionElements.formatForSlack(DescriptionElements(
        SlackEmoji.magnifyingGlass, "Google search integration", "-",
        "See the pages your coworkers are shared on top of Google search results"
      )))),
      SlackAttachment(color = None, text = Some(DescriptionElements.formatForSlack(DescriptionElements(
        SlackEmoji.pencil, "On the page", "-",
        "Ever wonder if your coworkers are already talking about an article you're looking at?",
        "If they are, I'll give you a link to the conversation."
      )))),
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
    val linkToFeed = LinkElement(pathCommander.ownKeepsFeedPage)
    val linkToUnsubscribe = LinkElement(pathCommander.slackPersonalDigestToggle(digest.slackMembership.slackTeamId, digest.slackMembership.slackUserId, turnOn = false))
    val text = DescriptionElements.unlines(List(
      DescriptionElements("You've sent", digest.numIngestedMessages, "links", inTheLast(digest.digestPeriod), ".",
        "I", "archived them" --> linkToFeed, "for you, and indexed the pages so you can search for them more easily."),
      DescriptionElements("If you don't want to get any more of these notifications,", "click here" --> linkToUnsubscribe)
    ))
    SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(text))
  }
}
