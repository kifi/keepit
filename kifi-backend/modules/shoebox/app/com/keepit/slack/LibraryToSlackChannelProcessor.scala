package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import com.keepit.model._
import com.keepit.slack.models._
import org.joda.time.Period

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[LibraryToSlackChannelProcessorImpl])
trait LibraryToSlackChannelProcessor {
  def processLibrary(libraryId: Id[Library]): Unit
  def findAndProcessLibraries(): Unit
}

@Singleton
class LibraryToSlackChannelProcessorImpl @Inject() (
  db: Database,
  libRepo: LibraryRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  slackIncomingWebhookInfoRepo: SlackIncomingWebhookInfoRepo,
  slackClient: SlackClient,
  libToChannelRepo: LibraryToSlackChannelRepo,
  clock: Clock,
  ktlRepo: KeepToLibraryRepo,
  keepRepo: KeepRepo,
  implicit val executionContext: ExecutionContext)
    extends LibraryToSlackChannelProcessor {

  private val gracePeriod = Period.minutes(10) // we will wait this long for a process to complete before we assume it is incompetent
  private val MAX_KEEPS_TO_SEND = 4
  def findAndProcessLibraries(): Unit = {
    val librariesThatNeedToBeProcessed = db.readOnlyReplica { implicit s =>
      libToChannelRepo.getRipeForProcessing(Limit(10), overrideProcessesOlderThan = clock.now.minus(gracePeriod))
    }
    librariesThatNeedToBeProcessed.foreach(processLibrary)
  }

  def processLibrary(libId: Id[Library]): Unit = {
    val (lib, integrationsToProcess) = db.readWrite { implicit s =>
      val lib = libRepo.get(libId)
      val integrations = libToChannelRepo.getForProcessingByLibrary(libId, overrideProcessesOlderThan = clock.now.minus(gracePeriod))
      (lib, integrations)
    }
    integrationsToProcess.foreach { lts =>
      val (webhook, keepsToSend) = db.readOnlyReplica { implicit s =>
        val ktls = ktlRepo.getByLibraryFromIdAndTime(libId, lts.lastProcessed)
        val keepsById = keepRepo.getByIds(ktls.map(_.keepId).toSet)
        val keeps = ktls.map(ktl => keepsById(ktl.keepId))
        val webhook = slackIncomingWebhookInfoRepo.getByIntegration(lts)
        (webhook, ktls zip keeps)
      }
      webhook.foreach { wh =>
        val slackFut = if (keepsToSend.length > MAX_KEEPS_TO_SEND) {
          slackClient.sendToSlack(wh.webhook.url, SlackMessage(s"${lib.name} has ${keepsToSend.length} new keeps"))
        } else {
          Future.sequence(keepsToSend.map {
            case (ktl, keep) => slackClient.sendToSlack(wh.webhook.url, SlackMessage(s"${keep.title.getOrElse("A keep")} was just added to ${lib.name}"))
          }).map(_ => ())
        }
        slackFut.onSuccess {
          case _ => db.readWrite { implicit s =>
            libToChannelRepo.finishProcessing(lts.withLastProcessed(clock.now, keepsToSend.lastOption.map(_._1.id.get)))
          }
        }
      }
    }
  }
}
