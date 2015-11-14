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
  def processLibrary(libId: Id[Library]): Future[Map[Id[LibraryToSlackChannel], Boolean]]
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
      libToChannelRepo.getLibrariesRipeForProcessing(Limit(10), overrideProcessesOlderThan = clock.now.minus(gracePeriod))
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
  def processLibrary(libId: Id[Library]): Future[Map[Id[LibraryToSlackChannel], Boolean]] = {
    import com.keepit.payments.DescriptionElements._
    val (lib, integrationsToProcess) = db.readWrite { implicit s =>
      val lib = libRepo.get(libId)
      val integrations = libToChannelRepo.getIntegrationsRipeForProcessingByLibrary(libId, overrideProcessesOlderThan = clock.now.minus(gracePeriod))
      log.info(s"[LTSCP] Found ${integrations.length} integrations ripe for processing")
      val integrationsToProcess = integrations.flatMap(ltsId => libToChannelRepo.markAsProcessing(ltsId))
      (lib, integrationsToProcess)
    }
    log.info(s"[LTSCP] Processing library $libId, pushing to ${integrationsToProcess.length} integrations")
    val slackPushes = integrationsToProcess.map { lts =>
      val (webhookOpt, messageOpt, lastKtlOpt) = db.readOnlyReplica { implicit s =>
        val webhook = slackIncomingWebhookInfoRepo.getByIntegration(lts)

        val ktls = ktlRepo.getByLibraryFromTimeAndId(libId, lts.lastProcessedAt, lts.lastProcessedKeep)
        val keepsById = keepRepo.getByIds(ktls.map(_.keepId).toSet)
        val message = ktls.length match {
          case noKeeps if noKeeps == 0 => None
          case justAFewKeeps if justAFewKeeps <= MAX_KEEPS_TO_SEND =>
            val msgs = ktls.flatMap(ktl => keepsById.get(ktl.keepId)).map(k => describeKeep(k, lib))
            Some(DescriptionElements.unlines(msgs))
          case lotsOfKeeps if lotsOfKeeps > MAX_KEEPS_TO_SEND =>
            Some(DescriptionElements(lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute), "has", ktls.length, "new keeps."))
        }
        (webhook, message, ktls.lastOption)
      }
      val slackPush = (webhookOpt, messageOpt) match {
        case (None, _) => Future.successful(false)
        case (Some(wh), None) =>
          log.info(s"[LTSCP] No new keeps to push to integration ${lts.id.get}")
          Future.successful(true)
        case (Some(wh), Some(msg)) =>
          slackClient.sendToSlack(wh.webhook.url, SlackMessage(DescriptionElements.formatForSlack(msg), Some(lts.slackChannelName.value)))
            .map { _ => true }
            .recover {
              case f: SlackAPIFailure =>
                log.info(s"[LTSCP] Failed to push Slack messages for integration ${lts.id.get} because $f")
                false
            }
      }
      slackPush.andThen {
        case res =>
          db.readWrite { implicit s =>
            libToChannelRepo.finishProcessing(lts.withLastProcessedAt(clock.now).withLastProcessedKeep(lastKtlOpt.map(_.id.get)))
          }
          if (res.isFailure) {
            log.info(s"[LTSCP] Failed to push Slack messages for integration ${lts.id.get}")
          }
      }.map { res => lts.id.get -> res }
    }
    Future.sequence(slackPushes).map(_.toMap)
  }
}
