package com.keepit.slack

import com.google.inject.Inject
import com.keepit.commanders.PathCommander
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core.futureExtensionOps
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.time.{ Clock, _ }
import com.keepit.common.util.{ DescriptionElements, LinkElement }
import com.keepit.model.LibrarySpace.OrganizationSpace
import com.keepit.model._
import com.keepit.slack.models._
import com.kifi.juggle._
import org.joda.time.Period

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

object SlackChannelDigestConfig {
  val minPeriodBetweenChannelDigests = Period.days(3)
  val minIngestedLinksForChannelDigest = 5

  // TODO(ryan): to release to the public, change canPushToChannel to `true`
  private val KifiSlackTeamId = SlackTeamId("T02A81H50")
  private val BrewstercorpSlackTeamId = SlackTeamId("T0FUL04N4")
  def canPushToChannel(channel: SlackChannel): Boolean = channel.slackTeamId == KifiSlackTeamId || channel.slackTeamId == BrewstercorpSlackTeamId // TODO(ryan): change this to `true`
}

class SlackChannelDigestNotificationActor @Inject() (
  db: Database,
  channelToLibRepo: SlackChannelToLibraryRepo,
  slackChannelRepo: SlackChannelRepo,
  libRepo: LibraryRepo,
  attributionRepo: KeepSourceAttributionRepo,
  ktlRepo: KeepToLibraryRepo,
  keepRepo: KeepRepo,
  orgConfigRepo: OrganizationConfigurationRepo,
  slackClient: SlackClientWrapper,
  pathCommander: PathCommander,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  implicit val ec: ExecutionContext,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends FortyTwoActor(airbrake) with ConcurrentTaskProcessingActor[Set[Id[SlackChannel]]] {

  protected val minConcurrentTasks = 0
  protected val maxConcurrentTasks = 1

  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SLACK)
  import SlackChannelDigestConfig._

  type Task = Set[Id[SlackChannel]]
  protected def pullTasks(limit: Int): Future[Seq[Task]] = {
    if (limit == 1) pullTask().map(Seq(_))
    else Future.successful(Seq.empty)
  }

  protected def processTasks(tasks: Seq[Task]): Map[Task, Future[Unit]] = {
    tasks.map { task => task -> processTask(task).imap(_ => ()) }.toMap
  }

  private def pullTask(): Future[Set[Id[SlackChannel]]] = {
    val now = clock.now
    db.readOnlyReplicaAsync { implicit session =>
      val ids = slackChannelRepo.getRipeForPushingDigestNotification(now minus minPeriodBetweenChannelDigests)
      slackChannelRepo.getByIds(ids.toSet).filter {
        case (_, slackChannel) => {
          val areNotifsEnabled = channelToLibRepo.getBySlackTeamAndChannel(slackChannel.slackTeamId, slackChannel.slackChannelId).exists { ctl =>
            ctl.space match {
              case OrganizationSpace(orgId) => orgConfigRepo.getByOrgId(orgId).settings.settingFor(Feature.SlackDigestNotification).contains(FeatureSetting.ENABLED)
              case _ => true
            }
          }
          areNotifsEnabled && canPushToChannel(slackChannel)
        }
      }.keySet
    }
  }

  private def processTask(ids: Set[Id[SlackChannel]]): Future[Map[SlackChannel, Try[Unit]]] = {
    for {
      channels <- db.readOnlyReplicaAsync { implicit s => slackChannelRepo.getByIds(ids.toSet).values }
      pushes <- FutureHelpers.accumulateRobustly(channels)(pushDigestNotificationForChannel)
    } yield pushes
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
      digestPeriod = new Period(slackChannel.lastNotificationAt, clock.now),
      ingestedLinks = ingestedLinks,
      libraries = librariesIngestedInto.values.toList
    )).filter(_.numIngestedLinks >= minIngestedLinksForChannelDigest)
  }

  private def describeChannelDigest(digest: SlackChannelDigest)(implicit session: RSession): SlackMessageRequest = {
    import DescriptionElements._
    SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(DescriptionElements.unlines(List(
      DescriptionElements("We have collected", digest.numIngestedLinks, "links from",
        digest.slackChannel.slackChannelName.value, inTheLast(digest.digestPeriod)),
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
