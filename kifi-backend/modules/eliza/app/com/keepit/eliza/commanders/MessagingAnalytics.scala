package com.keepit.eliza.commanders

import com.google.inject.{Singleton, Inject}
import com.keepit.heimdal._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.common.akka.SafeFuture
import com.keepit.model.{NotificationCategory, User}
import com.keepit.common.db.{ExternalId, Id}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.realtime.PushNotification
import com.keepit.common.db.slick.Database
import com.keepit.eliza.model._

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
      contextBuilder.addNotificationCategory(NotificationCategory.User.MESSAGE)
      contextBuilder += ("global", false)
      contextBuilder += ("muted", muted)
      contextBuilder += ("messageId", message.externalId.id)
      contextBuilder += ("threadId", thread.externalId.id)
      message.from.foreach { senderId => contextBuilder += ("senderId", senderId.id) }
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.WAS_NOTIFIED, sentAt))
    }
  }

  def sentPushNotificationForThread(userId: Id[User], notification: PushNotification): Unit = {
    val sentAt = currentDateTime
    SafeFuture {
      val contextBuilder = heimdalContextBuilder()
      contextBuilder += ("action", "sent")
      contextBuilder += ("channel", push)
      contextBuilder.addNotificationCategory(NotificationCategory.User.MESSAGE)
      contextBuilder += ("global", false)
      contextBuilder += ("threadId", notification.id.id)
      contextBuilder += ("pendingNotificationCount", notification.unvisitedCount)
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.WAS_NOTIFIED, sentAt))
    }
  }

  def sentGlobalNotification(userIds: Set[Id[User]], message: Message, thread: MessageThread): Unit = {
    val sentAt = currentDateTime
    SafeFuture {
      val contextBuilder = heimdalContextBuilder()
      contextBuilder += ("action", "sent")
      contextBuilder.addNotificationCategory(NotificationCategory.User.GLOBAL)
      contextBuilder += ("global", true)
      contextBuilder += ("messageId", message.externalId.id)
      contextBuilder += ("threadId", thread.externalId.id)
      val context = contextBuilder.build
      userIds.foreach { id => heimdal.trackEvent(UserEvent(id, context, UserEventTypes.WAS_NOTIFIED, sentAt)) }
    }
  }

  def clearedNotification(userId: Id[User], message: Message, thread: MessageThread, existingContext: HeimdalContext): Unit = {
    val clearedAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++= existingContext.data
      contextBuilder += ("action", "cleared")
      contextBuilder += ("channel", kifi)
      contextBuilder += ("messageId", message.externalId.id)
      contextBuilder += ("threadId", thread.externalId.id)
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.WAS_NOTIFIED, clearedAt))
    }
  }

  def addedParticipantsToConversation(userId: Id[User], newParticipants: Seq[Id[User]], thread: MessageThread, existingContext: HeimdalContext) = {
    val addedAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++= existingContext.data
      contextBuilder += ("action", "addedParticipants")
      contextBuilder += ("threadId", thread.externalId.id)
      contextBuilder += ("newParticipants", newParticipants.map(_.id))
      contextBuilder += ("participantsAdded", newParticipants.length)
      thread.uriId.foreach { uriId => contextBuilder += ("uriId", uriId.toString) }
      thread.participants.foreach(addParticipantsInfo(contextBuilder, _))
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.MESSAGED, addedAt))
    }
  }

  def sentMessage(userId: Id[User], message: Message, thread: MessageThread, isActuallyNew: Option[Boolean], existingContext: HeimdalContext) = {
    val sentAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++= existingContext.data
      isActuallyNew match {
        case Some(isNew) => {
          contextBuilder += ("action", "startedConversation")
          contextBuilder += ("isNewConversation", isNew)
        }
        case None => contextBuilder += ("action", "replied")
      }

      contextBuilder += ("threadId", thread.externalId.id)
      contextBuilder += ("messageId", message.externalId.id)
      thread.uriId.foreach { uriId => contextBuilder += ("uriId", uriId.toString) }
      thread.participants.foreach(addParticipantsInfo(contextBuilder, _))
      shoebox.getBookmarkByUriAndUser(thread.uriId.get, userId).foreach { bookmarkOption =>
        contextBuilder += ("isKeep", bookmarkOption.isDefined)
        val context = contextBuilder.build
        heimdal.trackEvent(UserEvent(userId, context, UserEventTypes.MESSAGED, sentAt))
        heimdal.trackEvent(UserEvent(userId, context, UserEventTypes.USED_KIFI, sentAt))
        heimdal.setUserProperties(userId, "lastMessaged" -> ContextDate(sentAt))

        // Anonymized event with page information
        anonymise(contextBuilder)
        bookmarkOption.map(_.url) orElse thread.url foreach contextBuilder.addUrlInfo
        heimdal.trackEvent(AnonymousEvent(contextBuilder.build, AnonymousEventTypes.MESSAGED, sentAt))
      }
    }
  }

  def changedMute(userId: Id[User], threadExternalId: ExternalId[MessageThread], mute: Boolean, existingContext: HeimdalContext): Unit = {
    val changedAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++= existingContext.data
      val action = if (mute) "mutedConversation" else "unmutedConversation"
      contextBuilder += ("action", action)
      contextBuilder += ("threadId", threadExternalId.id)
      val thread = db.readOnly { implicit session => threadRepo.get(threadExternalId) }
      thread.participants.foreach(addParticipantsInfo(contextBuilder, _))
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.CHANGED_SETTINGS, changedAt))
    }
  }

  private def addParticipantsInfo(contextBuilder: HeimdalContextBuilder, participants: MessageThreadParticipants): Unit = {
    val userParticipants = participants.allUsers.toSeq
    val externalParticipants = participants.allNonUsers.toSeq
    contextBuilder += ("participantsTotal", userParticipants.size + externalParticipants.size)
    contextBuilder += ("userParticipants", userParticipants.map(_.id))
    contextBuilder += ("userParticipantsTotal", userParticipants.size)
    // To be activated when external messaging is rolled out
    // contextBuilder += ("otherParticipants", externalParticipants.map(_.identifier))
    // contextBuilder += ("otherParticipantsTotal", externalParticipants.size)
    // contextBuilder += ("otherParticipantsKinds", externalParticipants.map(_.kind.name))
  }

  private def anonymise(contextBuilder: HeimdalContextBuilder): Unit =
    contextBuilder.anonymise("userParticipants", "newParticipants", "otherParticipants", "threadId", "messageId", "uriId")
}
