package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.PathCommander
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import com.keepit.model._
import com.keepit.payments.{ LinkElement, DescriptionElements }
import com.keepit.slack.models._
import com.keepit.social.BasicUser
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
  basicUserRepo: BasicUserRepo,
  pathCommander: PathCommander,
  implicit val executionContext: ExecutionContext)
    extends LibraryToSlackChannelProcessor with Logging {

  private val gracePeriod = Period.minutes(10) // we will wait this long for a process to complete before we assume it is incompetent
  private val MAX_KEEPS_TO_SEND = 4
  def findAndProcessLibraries(): Unit = {
    val librariesThatNeedToBeProcessed = db.readOnlyReplica { implicit s =>
      libToChannelRepo.getRipeForProcessing(Limit(10), overrideProcessesOlderThan = clock.now.minus(gracePeriod))
    }
    librariesThatNeedToBeProcessed.foreach(processLibrary)
  }

  private def getUser(id: Id[User])(implicit session: RSession): BasicUser = basicUserRepo.load(id)
  private def describeKeep(keep: Keep, lib: Library)(implicit session: RSession): DescriptionElements = {
    import com.keepit.payments.DescriptionElements._
    DescriptionElements(
      getUser(keep.userId), "just added",
      keep.title.getOrElse("a keep") --> LinkElement(keep.url),
      "to the", lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute), "library."
    )
  }
  def processLibrary(libId: Id[Library]): Unit = {
    import com.keepit.payments.DescriptionElements._
    val (lib, integrationsToProcess) = db.readWrite { implicit s =>
      val lib = libRepo.get(libId)
      val integrations = libToChannelRepo.getForProcessingByLibrary(libId, overrideProcessesOlderThan = clock.now.minus(gracePeriod))
      (lib, integrations)
    }
    log.info(s"[LTSC] Processing library $libId, pushing to ${integrationsToProcess.length} integrations")
    integrationsToProcess.foreach { lts =>
      val (webhook, msg, lastKtlOpt) = db.readOnlyReplica { implicit s =>
        val webhook = slackIncomingWebhookInfoRepo.getByIntegration(lts)

        val ktls = ktlRepo.getByLibraryFromTimeAndId(libId, lts.lastProcessedAt, lts.lastProcessedKeep)
        val keepsById = keepRepo.getByIds(ktls.map(_.keepId).toSet)
        log.info(s"[LTSC] For integration ${lts.id.get}, need to push info about ${ktls.length} keeps")
        val message = if (ktls.length > MAX_KEEPS_TO_SEND) {
          DescriptionElements(lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute), "has", ktls.length, "new keeps.")
        } else {
          val msgs = ktls.flatMap(ktl => keepsById.get(ktl.keepId)).map(k => describeKeep(k, lib))
          DescriptionElements.unlines(msgs)
        }
        (webhook, message, ktls.lastOption)
      }
      webhook.foreach { wh =>
        slackClient.sendToSlack(wh.webhook.url, SlackMessage(DescriptionElements.formatForSlack(msg), Some(lts.slackChannelName.value)))
          .onComplete { res =>
            db.readWrite { implicit s =>
              libToChannelRepo.finishProcessing(lts.withLastProcessedAt(clock.now).withLastProcessedKeep(lastKtlOpt.map(_.id.get)))
            }
            if (res.isFailure) log.info(s"[LTSCP] Failed to push Slack messages for integration ${lts.id.get}")
          }
      }
    }
  }
}
