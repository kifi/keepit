package com.keepit.eliza

import com.google.inject.{Singleton, Inject}
import com.keepit.heimdal._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.common.akka.SafeFuture
import com.keepit.model.User
import com.keepit.common.db.Id
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.shoebox.ShoeboxServiceClient

@Singleton
class MessagingAnalytics @Inject() (
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  heimdal: HeimdalServiceClient,
  shoebox: ShoeboxServiceClient,
  airbrake: AirbrakeNotifier) {

  def sentNotificationForMessage(userId: Id[User], message: Message, thread: MessageThread, muted: Boolean): Unit = {
    val sentAt = currentDateTime
    SafeFuture {
      val contextBuilder = heimdalContextBuilder()
      contextBuilder += ("action", "sent")
      contextBuilder += ("channel", "kifi")
      contextBuilder += ("category", NotificationCategory.MESSAGE)
      contextBuilder += ("global", false)
      contextBuilder += ("muted", muted)
      contextBuilder += ("messageExternalId", message.externalId.id)
      contextBuilder += ("threadId", thread.id.get.id)
      heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, UserEventTypes.WAS_NOTIFIED, sentAt))
    }
  }

  def sentGlobalNotification(userIds: Set[Id[User]], message: Message, thread: MessageThread): Unit = {
    val sentAt = currentDateTime
    SafeFuture {
      val contextBuilder = heimdalContextBuilder()
      contextBuilder += ("action", "sent")
      contextBuilder += ("channel", "kifi")
      contextBuilder += ("category", NotificationCategory.GLOBAL)
      contextBuilder += ("global", true)
      contextBuilder += ("messageExternalId", message.externalId.id)
      contextBuilder += ("threadId", thread.id.get.id)
      val context = contextBuilder.build
      userIds.foreach { id => heimdal.trackEvent(UserEvent(id.id, context, UserEventTypes.WAS_NOTIFIED, sentAt)) }
    }
  }

  def clearedNotification(userId: Id[User], message: Message, thread: MessageThread, existingContext: HeimdalContext): Unit = {
    val clearedAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++ existingContext.data
      contextBuilder += ("action", "cleared")
      contextBuilder += ("channel", "kifi")
      contextBuilder += ("messageExternalId", message.externalId.id)
      contextBuilder += ("threadId", thread.id.get.id)
      heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, UserEventTypes.WAS_NOTIFIED, clearedAt))
    }
  }

  def addedParticipantsToConversation(userId: Id[User], newParticipants: Seq[Id[User]], thread: MessageThread, existingContext: HeimdalContext) = {
    val addedAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++ existingContext.data
      contextBuilder += ("action", "addedParticipants")
      contextBuilder += ("threadId", thread.id.get.id)
      contextBuilder += ("newParticipants", newParticipants.map(_.id))
      contextBuilder += ("participantsAdded", newParticipants.length)
      thread.participants.foreach { participants =>
        val userParticipants = participants.allUsers.toSeq
        val externalParticipants = participants.allNonUsers.toSeq
        contextBuilder += ("participantsTotal", userParticipants.size + externalParticipants.size)
        contextBuilder += ("userParticipants", userParticipants.map(_.id))
        contextBuilder += ("userParticipantsTotal", userParticipants.size)
        contextBuilder += ("externalParticipants", externalParticipants.map(_.identifier))
        contextBuilder += ("externalParticipantsTotal", externalParticipants.size)
        contextBuilder += ("externalParticipantsKinds", externalParticipants.map(_.kind.name))
      }
      heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, UserEventTypes.MESSAGED, addedAt))
    }
  }

  def sentMessage(userId: Id[User], message: Message, thread: MessageThread, isActuallyNew: Option[Boolean], existingContext: HeimdalContext) = {
    val sentAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++ existingContext.data
      isActuallyNew match {
        case Some(isNew) => {
          contextBuilder += ("action", "startedConversation")
          contextBuilder += ("isActuallyNew", isNew)
        }
        case None => contextBuilder += ("action", "replied")
      }

      contextBuilder += ("threadId", thread.id.get.id)
      contextBuilder += ("messageExternalId", message.externalId.id)
      thread.participants.foreach { participants =>
        val userRecipients = participants.allUsersExcept(userId).toSeq
        val externalRecipients = participants.allNonUsers.toSeq
        contextBuilder += ("recipientsTotal", userRecipients.size + participants.allNonUsers.size)
        contextBuilder += ("userRecipients", userRecipients.map(_.id))
        contextBuilder += ("userRecipientsTotal", userRecipients.size)
        contextBuilder += ("externalRecipients", externalRecipients.map(_.identifier))
        contextBuilder += ("externalRecipientsTotal", externalRecipients.size)
        contextBuilder += ("externalRecipientsKinds", externalRecipients.map(_.kind.name))
      }

      shoebox.getBookmarkByUriAndUser(thread.uriId.get, userId).foreach { bookmarkOption =>
        contextBuilder += ("isKeep", bookmarkOption.isDefined)
        heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, UserEventTypes.MESSAGED, sentAt))
      }
    }
  }
}
