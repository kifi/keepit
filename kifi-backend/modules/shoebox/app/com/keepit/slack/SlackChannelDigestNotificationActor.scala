package com.keepit.slack

import com.google.inject.Inject
import com.keepit.commanders.PathCommander
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core.{ anyExtensionOps, futureExtensionOps }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.time.{ Clock, _ }
import com.keepit.common.util.{ DescriptionElements, LinkElement }
import com.keepit.model._
import com.keepit.slack.models._
import com.kifi.juggle._
import org.joda.time.{ Duration, Period }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

object SlackChannelDigestConfig {
  val minPeriodBetweenChannelDigests = Period.days(3)
  val minIngestedLinksForChannelDigest = 5
}

class SlackChannelDigestNotificationActor @Inject() (
  db: Database,
  slackTeamRepo: SlackTeamRepo,
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
      val ripeIds = slackChannelRepo.getRipeForPushingDigestNotification(now minus minPeriodBetweenChannelDigests).toSet
      val channels = slackChannelRepo.getByIds(ripeIds).values.toSeq
      val teamIds = channels.map(_.slackTeamId).toSet
      val teamById = slackTeamRepo.getBySlackTeamIds(teamIds)
      val orgIds = teamById.values.flatMap(_.organizationId).toSet
      val orgConfigById = orgConfigRepo.getByOrgIds(orgIds)
      def canSendDigestTo(channel: SlackChannel) = {
        teamById.get(channel.slackTeamId).exists { team =>
          team.organizationId.flatMap(orgConfigById.get).exists { config =>
            config.settings.settingFor(Feature.SlackDigestNotification).contains(FeatureSetting.ENABLED)
          }
        }
      }
      channels.filter(canSendDigestTo).map(_.id.get).toSet tap { task => log.info(s"[SLACK-CHANNEL-DIGEST] Pulled $task") }
    }
  }

  private def processTask(ids: Set[Id[SlackChannel]]): Future[Map[Seq[SlackChannel], Try[Boolean]]] = {
    val result = for {
      channelsByTeamId <- db.readOnlyReplicaAsync { implicit s => slackChannelRepo.getByIds(ids).values.toSeq.groupBy(_.slackTeamId) }
      pushes <- FutureHelpers.accumulateRobustly(channelsByTeamId.values)(pushDigestNotificationForOneChannelInTeam)
    } yield pushes
    result.andThen {
      case Success(pushes) =>
        val failures = pushes.collect {
          case (chs, Failure(fail)) => chs.head.slackTeamId -> fail.getMessage
          case (chs, Success(false)) => chs.head.slackTeamId -> "could not generate a useful message for any of" + chs.map(_.slackChannelId).mkString("{", ",", "}")
        }
        if (failures.nonEmpty) slackLog.error("Failed to push channel digests:", failures.mkString("[", ",", "]"))
      case Failure(fail) => airbrake.notify("Failed to process tasks in the slack channel digest actor", fail)
    }
  }

  private def createChannelDigest(slackChannel: SlackChannel)(implicit session: RSession): Option[SlackChannelDigest] = {
    val ingestions = channelToLibRepo.getBySlackTeamAndChannel(slackChannel.slackTeamId, slackChannel.slackChannelId).filter(_.isWorking)
    val librariesIngestedInto = libRepo.getActiveByIds(ingestions.map(_.libraryId).toSet)
    val ingestedLinks = {
      val newKeepIds = ktlRepo.getByLibrariesAddedSince(librariesIngestedInto.keySet, slackChannel.unnotifiedSince).map(_.keepId).toSet
      val newSlackKeepsById = keepRepo.getByIds(newKeepIds).filter { case (_, keep) => keep.source == KeepSource.slack }
      attributionRepo.getByKeepIds(newSlackKeepsById.keySet).collect {
        case (kId, SlackAttribution(msg)) if msg.channel.id == slackChannel.slackChannelId =>
          newSlackKeepsById.get(kId).map(_.url)
      }.flatten.toSet
    }

    Some(SlackChannelDigest(
      slackChannel = slackChannel,
      digestPeriod = new Duration(slackChannel.unnotifiedSince, clock.now),
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
        DescriptionElements.unwordsPretty {
          digest.libraries.map(lib => lib.name --> LinkElement(pathCommander.libraryPageViaSlack(lib, digest.slackChannel.slackTeamId)))
        })
    )))).quiet
  }
  private def pushDigestNotificationForOneChannelInTeam(channels: Seq[SlackChannel]): Future[Boolean] = {
    val now = clock.now
    val channelAndMessage = db.readOnlyMaster { implicit s =>
      channels.sortBy(_.unnotifiedSince).toStream.flatMap { channel =>
        createChannelDigest(channel).map(describeChannelDigest).map(channel -> _)
      }.headOption
    }
    log.info(s"[SLACK-CHANNEL-DIGEST] Trying to push channel digest for ${channels.map(_.slackChannelId).mkString(",")}, got msg $channelAndMessage")
    val pushOpt = channelAndMessage.map {
      case (channel, msg) =>
        slackClient.sendToSlackChannel(channel.slackTeamId, channel.idAndName, msg).imap(_ => true).andThen {
          case Success(_) =>
            db.readWrite { implicit s =>
              slackChannelRepo.save(slackChannelRepo.get(channel.id.get).withLastNotificationAtLeast(now))
            }
            slackLog.info("Pushed a digest to", channel.slackChannelName.value, "in team", channel.slackTeamId.value)
          case Failure(fail) =>
            slackLog.warn("Failed to push a digest to", channel.slackChannelName.value, "in team", channel.slackTeamId.value)
        }
    }
    pushOpt.getOrElse(Future.successful(false))
  }
}
