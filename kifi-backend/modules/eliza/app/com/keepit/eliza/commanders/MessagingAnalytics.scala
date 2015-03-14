package com.keepit.eliza.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.heimdal._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.akka.SafeFuture
import com.keepit.model.{ NotificationCategory, User }
import com.keepit.common.db.{ ExternalId, Id }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.realtime.{ MessageThreadPushNotification, SimplePushNotification, PushNotification }
import com.keepit.common.db.slick.Database
import com.keepit.eliza.model._

@Singleton
class MessagingAnalytics @Inject() (
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    heimdal: HeimdalServiceClient,
    shoebox: ShoeboxServiceClient,
    threadRepo: MessageThreadRepo,
    db: Database) extends Logging {

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
      message.from match {
        case MessageSender.User(senderId) => contextBuilder += ("senderId", senderId.id)
        case MessageSender.NonUser(nup) => contextBuilder += ("senderId", nup.kind + "::" + nup.identifier)
        case _ =>
      }
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.WAS_NOTIFIED, sentAt))
    }
  }

  def sentPushNotification(userId: Id[User], notification: PushNotification): Unit = notification match {
    case messageNotification: MessageThreadPushNotification => sentPushNotificationForThread(userId, messageNotification)
    case simplePushNotification: SimplePushNotification => sentSimplePushNotification(userId, simplePushNotification)
  }

  private def sentPushNotificationForThread(userId: Id[User], notification: MessageThreadPushNotification): Unit = {
    val sentAt = currentDateTime
    SafeFuture {
      val contextBuilder = heimdalContextBuilder()
      contextBuilder += ("action", "sent")
      contextBuilder += ("channel", push)
      contextBuilder.addNotificationCategory(NotificationCategory.User.MESSAGE)
      contextBuilder += ("global", false)
      contextBuilder += ("category", "messageThread")
      contextBuilder += ("threadId", notification.id.id)
      contextBuilder += ("pendingNotificationCount", notification.unvisitedCount)
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.WAS_NOTIFIED, sentAt))
    }
  }

  private def sentSimplePushNotification(userId: Id[User], notification: SimplePushNotification): Unit = {
    val sentAt = currentDateTime
    SafeFuture {
      val contextBuilder = heimdalContextBuilder()
      contextBuilder += ("action", "sent")
      contextBuilder += ("channel", push)
      contextBuilder.addNotificationCategory(NotificationCategory.User.MESSAGE)
      contextBuilder += ("global", false)
      contextBuilder += ("category", "simple")
      contextBuilder += ("subcategory", notification.category.name)
      contextBuilder += ("exp_engagement_push", notification.experiment.name)
      contextBuilder += ("pendingNotificationCount", notification.unvisitedCount)
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.WAS_NOTIFIED, sentAt))
    }
  }

  def sentGlobalNotification(userIds: Set[Id[User]], message: Message, thread: MessageThread, category: NotificationCategory): Unit = {
    val sentAt = currentDateTime
    SafeFuture {
      val contextBuilder = heimdalContextBuilder()
      contextBuilder += ("action", "sent")
      contextBuilder += ("channel", kifi)
      contextBuilder.addNotificationCategory(category)
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

  def addedParticipantsToConversation(userId: Id[User], newUserParticipants: Seq[Id[User]], newNonUserParticipants: Seq[NonUserParticipant], thread: MessageThread, existingContext: HeimdalContext) = {
    val addedAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++= existingContext.data
      contextBuilder += ("action", "addedParticipants")
      contextBuilder += ("threadId", thread.externalId.id)
      if (newUserParticipants.nonEmpty) { contextBuilder += ("newUserParticipants", newUserParticipants.map(_.id)) }
      if (newNonUserParticipants.nonEmpty) { contextBuilder += ("newNonUserParticipants", newNonUserParticipants.map(_.identifier)) }
      contextBuilder += ("newParticipantKinds", newUserParticipants.map(_ => "user") ++ newNonUserParticipants.map(_.kind.name))
      contextBuilder += ("participantsAdded", newNonUserParticipants.length)
      thread.uriId.foreach { uriId => contextBuilder += ("uriId", uriId.toString) }
      thread.participants.foreach(addParticipantsInfo(contextBuilder, _))
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.MESSAGED, addedAt))
    }
  }

  def sentMessage(sender: MessageSender, message: Message, thread: MessageThread, isActuallyNew: Option[Boolean], existingContext: HeimdalContext) = {
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
      message.source.foreach { source => contextBuilder += ("source", source.value) }
      thread.uriId.foreach { uriId => contextBuilder += ("uriId", uriId.toString) }
      thread.participants.foreach(addParticipantsInfo(contextBuilder, _))

      sender match {
        case MessageSender.User(userId) => {
          val uriId = thread.uriId.get
          shoebox.getBasicKeeps(userId, Set(uriId)).foreach { basicKeeps =>
            contextBuilder += ("isKeep", basicKeeps.get(uriId).exists(_.nonEmpty))
            val context = contextBuilder.build
            heimdal.trackEvent(UserEvent(userId, context, UserEventTypes.MESSAGED, sentAt))
            heimdal.trackEvent(UserEvent(userId, context, UserEventTypes.USED_KIFI, sentAt))
            heimdal.setUserProperties(userId, "lastMessaged" -> ContextDate(sentAt))

            // Anonymized event with page information
            anonymise(contextBuilder)
            thread.url foreach contextBuilder.addUrlInfo
            heimdal.trackEvent(AnonymousEvent(contextBuilder.build, AnonymousEventTypes.MESSAGED, sentAt))
          }
        }

        case MessageSender.NonUser(nonUser) => heimdal.trackEvent(NonUserEvent(nonUser.identifier, nonUser.kind, contextBuilder.build, NonUserEventTypes.MESSAGED, sentAt))

        case MessageSender.System => heimdal.trackEvent(SystemEvent(contextBuilder.build, SystemEventTypes.MESSAGED))
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
      val thread = db.readOnlyReplica { implicit session => threadRepo.get(threadExternalId) }
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
    contextBuilder += ("otherParticipants", externalParticipants.map(_.identifier))
    contextBuilder += ("otherParticipantsTotal", externalParticipants.size)
    contextBuilder += ("otherParticipantsKinds", externalParticipants.map(_.kind.name))
  }

  private def anonymise(contextBuilder: HeimdalContextBuilder): Unit =
    contextBuilder.anonymise("userParticipants", "newParticipants", "otherParticipants", "threadId", "messageId", "uriId")
}
