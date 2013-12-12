package com.keepit.eliza

import com.google.inject.{Singleton, Inject}
import com.keepit.heimdal._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.common.akka.SafeFuture
import com.keepit.model.User
import com.keepit.common.db.{ExternalId, Id}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.realtime.PushNotification
import com.keepit.common.db.slick.Database

@Singleton
class MessagingAnalytics @Inject() (
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  heimdal: HeimdalServiceClient,
  shoebox: ShoeboxServiceClient,
  threadRepo: MessageThreadRepo,
  db: Database) {

  private val kifi = "kifi"
  private val push = "push"

  // TODO: DRY up sent notification analytics

  def sentNotificationForMessage(userId: Id[User], message: Message, thread: MessageThread, muted: Boolean): Unit = {
    val sentAt = currentDateTime
    SafeFuture {
      val contextBuilder = heimdalContextBuilder()
      contextBuilder += ("action", "sent")
      contextBuilder += ("channel", kifi)
      contextBuilder += ("category", NotificationCategory.Personal.MESSAGE.category)
      contextBuilder += ("global", false)
      contextBuilder += ("muted", muted)
      contextBuilder += ("messageExternalId", message.externalId.id)
      contextBuilder += ("threadExternalId", thread.externalId.id)
      message.from.foreach { senderId => contextBuilder += ("messageSenderId", senderId.id) }
      heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, UserEventTypes.WAS_NOTIFIED, sentAt))
    }
  }

  def sentPushNotificationForThread(userId: Id[User], notification: PushNotification): Unit = {
    val sentAt = currentDateTime
    SafeFuture {
      val contextBuilder = heimdalContextBuilder()
      contextBuilder += ("action", "sent")
      contextBuilder += ("channel", push)
      contextBuilder += ("category", NotificationCategory.Personal.MESSAGE.category)
      contextBuilder += ("global", false)
      contextBuilder += ("threadExternalId", notification.id.id)
      contextBuilder += ("pendingNotificationCount", notification.unvisitedCount)
      heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, UserEventTypes.WAS_NOTIFIED, sentAt))
    }
  }

  def sentGlobalNotification(userIds: Set[Id[User]], message: Message, thread: MessageThread, category: NotificationCategory = NotificationCategory.Global.ANNOUNCEMENT): Unit = {
    val sentAt = currentDateTime
    SafeFuture {
      val contextBuilder = heimdalContextBuilder()
      contextBuilder += ("action", "sent")
      contextBuilder += ("channel", kifi)
      contextBuilder += ("category", category.category)
      contextBuilder += ("global", true)
      contextBuilder += ("messageExternalId", message.externalId.id)
      contextBuilder += ("threadExternalId", thread.externalId.id)
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
      contextBuilder += ("channel", kifi)
      contextBuilder += ("messageExternalId", message.externalId.id)
      contextBuilder += ("threadExternalId", thread.externalId.id)
      heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, UserEventTypes.WAS_NOTIFIED, clearedAt))
    }
  }

  def addedParticipantsToConversation(userId: Id[User], newParticipants: Seq[Id[User]], thread: MessageThread, existingContext: HeimdalContext) = {
    val addedAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++ existingContext.data
      contextBuilder += ("action", "addedParticipants")
      contextBuilder += ("threadExternalId", thread.externalId.id)
      contextBuilder += ("newParticipants", newParticipants.map(_.id))
      contextBuilder += ("participantsAdded", newParticipants.length)
      thread.participants.foreach(addParticipantsInfo(contextBuilder, _))
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

      contextBuilder += ("threadExternalId", thread.externalId.id)
      contextBuilder += ("messageExternalId", message.externalId.id)
      thread.participants.foreach(addRecipientsInfo(contextBuilder, userId, _))
      shoebox.getBookmarkByUriAndUser(thread.uriId.get, userId).foreach { bookmarkOption =>
        contextBuilder += ("isKeep", bookmarkOption.isDefined)
        val context = contextBuilder.build
        heimdal.trackEvent(UserEvent(userId.id, context, UserEventTypes.MESSAGED, sentAt))
        heimdal.trackEvent(UserEvent(userId.id, context, UserEventTypes.USED_KIFI, sentAt))
        heimdal.setUserProperties(userId, "lastMessaged" -> ContextDate(sentAt))
      }
    }
  }

  def changedMute(userId: Id[User], threadExternalId: ExternalId[MessageThread], mute: Boolean, existingContext: HeimdalContext): Unit = {
    val changedAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++ existingContext.data
      val action = if (mute) "mutedConversation" else "unmutedConversation"
      contextBuilder += ("action", action)
      contextBuilder += ("threadExternalId", threadExternalId.id)
      val thread = db.readOnly { implicit session => threadRepo.get(threadExternalId) }
      thread.participants.foreach(addParticipantsInfo(contextBuilder, _))
      heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, UserEventTypes.CHANGED_SETTINGS, changedAt))
    }
  }

  private def addParticipantsInfo(contextBuilder: HeimdalContextBuilder, participants: MessageThreadParticipants): Unit = {
    val userParticipants = participants.allUsers.toSeq
    val externalParticipants = participants.allNonUsers.toSeq
    contextBuilder += ("participantsTotal", userParticipants.size + externalParticipants.size)
    contextBuilder += ("userParticipants", userParticipants.map(_.id))
    contextBuilder += ("userParticipantsTotal", userParticipants.size)
    contextBuilder += ("externalParticipants", externalParticipants.map(_.identifier))
    contextBuilder += ("externalParticipantsTotal", externalParticipants.size)
    contextBuilder += ("externalParticipantsKinds", externalParticipants.map(_.kind.name))
  }

  private def addRecipientsInfo(contextBuilder: HeimdalContextBuilder, senderId: Id[User], participants: MessageThreadParticipants): Unit = {
    val userRecipients = participants.allUsersExcept(senderId).toSeq
    val externalRecipients = participants.allNonUsers.toSeq
    contextBuilder += ("recipientsTotal", userRecipients.size + participants.allNonUsers.size)
    contextBuilder += ("userRecipients", userRecipients.map(_.id))
    contextBuilder += ("userRecipientsTotal", userRecipients.size)
    contextBuilder += ("externalRecipients", externalRecipients.map(_.identifier))
    contextBuilder += ("externalRecipientsTotal", externalRecipients.size)
    contextBuilder += ("externalRecipientsKinds", externalRecipients.map(_.kind.name))
  }
}
