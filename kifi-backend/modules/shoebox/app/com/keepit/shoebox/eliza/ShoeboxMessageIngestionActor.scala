package com.keepit.shoebox.eliza

import com.google.inject.Inject
import com.keepit.commanders.{TagCommander, Hashtags, KeepMutator, KeepCommander}
import com.keepit.common.akka.{ FortyTwoActor, SafeFuture }
import com.keepit.common.core._
import com.keepit.common.db.SequenceNumber
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.util.DeltaSet
import com.keepit.discussion.{ CrossServiceMessage, Message }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient, LibraryToSlackChannelPusher }
import com.kifi.juggle.BatchProcessingActor

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Failure

object ShoeboxMessageIngestionActor {
  val shoeboxMessageSeq = Name[SequenceNumber[Message]]("shoebox_message")
  val fetchSize: Int = 100
}

class ShoeboxMessageIngestionActor @Inject() (
  db: Database,
  systemValueRepo: SystemValueRepo,
  keepRepo: KeepRepo,
  keepMutator: KeepMutator,
  eliza: ElizaServiceClient,
  airbrake: AirbrakeNotifier,
  slackPusher: LibraryToSlackChannelPusher,
  tagCommander: TagCommander,
  implicit val executionContext: ExecutionContext,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends FortyTwoActor(airbrake) with BatchProcessingActor[CrossServiceMessage] {

  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SHOEBOX)
  import ShoeboxMessageIngestionActor._

  protected def nextBatch: Future[Seq[CrossServiceMessage]] = {
    log.info(s"Ingesting new messages from Eliza...")
    SafeFuture {
      db.readOnlyMaster { implicit session =>
        systemValueRepo.getSequenceNumber(shoeboxMessageSeq) getOrElse SequenceNumber.ZERO
      }
    } flatMap { seqNum =>
      eliza.getMessagesChanged(seqNum, fetchSize)
    }
  } andThen {
    case Failure(error) =>
      log.error("Could not fetch new messages from Eliza", error)
      slackLog.error("Could not fetch new messages from Eliza:", error.getMessage)
  }

  protected def processBatch(messages: Seq[CrossServiceMessage]): Future[Unit] = SafeFuture {
    messages.map(_.seq).maxOpt.foreach { maxSeq =>
      val messagesByKeep = messages.groupBy(_.keep)
      db.readWrite { implicit session =>
        val keeps = keepRepo.getActiveByIds(messagesByKeep.keySet).values
        session.onTransactionSuccess {
          slackPusher.schedule(keeps.flatMap(_.recipients.libraries).toSet)
        }
        messagesByKeep.foreach {
          case (keepId, msgs) =>
            // Functions that handle keep changes
            def updateMessageSeq() = {
              val keep = keepRepo.get(keepId)
              val updatedKeep = keep.withMessageSeq(msgs.map(_.seq).max)
              if (updatedKeep != keep) keepRepo.save(updatedKeep)
            }
            def updateLastActivity() = {
              val lastMessageTime = msgs.map(_.sentAt).maxBy(_.getMillis)
              keepMutator.updateLastActivityAtIfLater(keepId, lastMessageTime)
            }
            def handleKeepEvents() = msgs.flatMap(_.auxData).foreach {
              case KeepEventData.ModifyRecipients(editor, KeepRecipientsDiff(users, libraries, emails)) =>
                val diff = KeepRecipientsDiff(
                  users = DeltaSet.empty.addAll(users.added),
                  emails = DeltaSet.empty.addAll(emails.added),
                  libraries = DeltaSet.empty.addAll(libraries.added)
                )
                keepMutator.unsafeModifyKeepRecipients(keepId, diff, userAttribution = Some(editor))
              case KeepEventData.EditTitle(_, _, _) =>
            }
            def syncTagsFromMessages() = msgs.foreach { msg =>
              val tags = Hashtags.findAllHashtagNames(msg.text).filter(_.nonEmpty).map(Hashtag(_))
              val existing = tagCommander.getTagsForMessage(msg.id).toSet
              val tagsToAdd = tags.diff(existing)
              val tagsToRemove = existing.diff(tags)
              tagCommander.addTagsToKeep(keepId, tagsToAdd, msg.sentBy.flatMap(_.left.toOption), Some(msg.id))
              tagCommander.removeTagsFromKeeps(Set(keepId), tagsToRemove)
            }

            // Apply all of the functions in sequence
            updateMessageSeq()
            updateLastActivity()
            handleKeepEvents()
            syncTagsFromMessages()
        }
        systemValueRepo.setSequenceNumber(shoeboxMessageSeq, maxSeq)
      }
      log.info(s"Ingested ${messages.length} messages from Eliza")
    }
  }
}
