package com.keepit.eliza.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.discussion.Message
import com.keepit.heimdal._
import com.keepit.realtime._
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.core.optionExtensionOps
import com.keepit.common.akka.SafeFuture
import com.keepit.model.{ KeepEventSourceKind, Keep, Organization, NotificationCategory, User }
import com.keepit.common.db.{ ExternalId, Id }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.realtime.{ MessageThreadPushNotification, SimplePushNotification, PushNotification }
import com.keepit.common.db.slick.Database
import com.keepit.eliza.model._

import scala.concurrent.Future
import scala.util.Random

@Singleton
class MessagingAnalytics @Inject() (
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    heimdal: HeimdalServiceClient,
    shoebox: ShoeboxServiceClient,
    threadRepo: MessageThreadRepo,
    db: Database,
    clock: Clock,
    implicit val publicIdConfig: PublicIdConfiguration) extends Logging {

  private val kifi = "kifi"
  private val push = "push"

  // TODO: DRY up sent notification analytics

  def sentNotificationForMessage(userId: Id[User], message: ElizaMessage, thread: MessageThread, muted: Boolean): Unit = {
    val sentAt = currentDateTime
    SafeFuture {
      val contextBuilder = heimdalContextBuilder()
      contextBuilder += ("action", "sent")
      contextBuilder += ("channel", kifi)
      contextBuilder.addNotificationCategory(NotificationCategory.User.MESSAGE)
      contextBuilder += ("global", false)
      contextBuilder += ("muted", muted)
      contextBuilder += ("messageId", message.pubId.id)
      contextBuilder += ("threadId", thread.pubKeepId.id)
      message.from match {
        case MessageSender.User(senderId) => contextBuilder += ("senderId", senderId.id)
        case MessageSender.NonUser(nup) => contextBuilder += ("senderId", nup.kind + "::" + nup.identifier)
        case _ =>
      }
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.WAS_NOTIFIED, sentAt))
    }
  }

  def sentPushNotification(userId: Id[User], deviceType: DeviceType, notification: PushNotification): Unit = notification match {
    case messageNotification: MessageThreadPushNotification => sentPushNotificationForThread(userId, deviceType, messageNotification)
    case simplePushNotification: SimplePushNotification => sentSimplePushNotification(userId, deviceType, simplePushNotification)
    case libraryUpdatePushNotification: LibraryUpdatePushNotification => sentLibraryUpdatePushNotification(userId, deviceType, libraryUpdatePushNotification)
    case userPushNotification: UserPushNotification => sentUserPushNotification(userId, deviceType, userPushNotification)
    case orgPushNotification: OrganizationPushNotification => sentOrgPushNotification(userId, deviceType, orgPushNotification)
    case messageCountPushNotification: MessageCountPushNotification => //do nothing
  }

  private def sentPushNotificationForThread(userId: Id[User], deviceType: DeviceType, notification: MessageThreadPushNotification): Unit = {
    val sentAt = currentDateTime
    SafeFuture {
      val contextBuilder = heimdalContextBuilder()
      contextBuilder += ("action", "sent")
      contextBuilder += ("channel", push)
      contextBuilder.addNotificationCategory(NotificationCategory.User.MESSAGE)
      contextBuilder += ("global", false)
      contextBuilder += ("category", "messageThread")
      contextBuilder += ("os", deviceType.name)
      contextBuilder += ("threadId", notification.id.id)
      contextBuilder += ("pendingNotificationCount", notification.unvisitedCount)
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.WAS_NOTIFIED, sentAt))
    }
  }

  private def sentSimplePushNotification(userId: Id[User], deviceType: DeviceType, notification: SimplePushNotification): Unit = {
    val sentAt = currentDateTime
    SafeFuture {
      val contextBuilder = heimdalContextBuilder()
      contextBuilder += ("action", "sent")
      contextBuilder += ("channel", push)
      contextBuilder.addNotificationCategory(NotificationCategory.User.MESSAGE)
      contextBuilder += ("global", false)
      contextBuilder += ("category", "simple")
      contextBuilder += ("subcategory", notification.category.name)
      contextBuilder += ("os", deviceType.name)
      contextBuilder += ("exp_engagement_push", notification.experiment.name)
      contextBuilder += ("pendingNotificationCount", notification.unvisitedCount)
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.WAS_NOTIFIED, sentAt))
    }
  }

  private def sentLibraryUpdatePushNotification(userId: Id[User], deviceType: DeviceType, notification: LibraryUpdatePushNotification): Unit = {
    val sentAt = currentDateTime
    SafeFuture {
      val contextBuilder = heimdalContextBuilder()
      contextBuilder += ("action", "sent")
      contextBuilder += ("channel", push)
      contextBuilder.addNotificationCategory(NotificationCategory.User.MESSAGE)
      contextBuilder += ("global", false)
      contextBuilder += ("category", "simple")
      contextBuilder += ("subcategory", notification.category.name)
      contextBuilder += ("library", notification.libraryId.id)
      contextBuilder += ("os", deviceType.name)
      contextBuilder += ("exp_engagement_push", notification.experiment.name)
      contextBuilder += ("pendingNotificationCount", notification.unvisitedCount)
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.WAS_NOTIFIED, sentAt))
    }
  }

  private def sentUserPushNotification(userId: Id[User], deviceType: DeviceType, notification: UserPushNotification): Unit = {
    val sentAt = currentDateTime
    SafeFuture {
      val contextBuilder = heimdalContextBuilder()
      contextBuilder += ("action", "sent")
      contextBuilder += ("channel", push)
      contextBuilder.addNotificationCategory(NotificationCategory.User.MESSAGE)
      contextBuilder += ("global", false)
      contextBuilder += ("category", "simple")
      contextBuilder += ("subcategory", notification.category.name)
      contextBuilder += ("user", userId.id)
      contextBuilder += ("os", deviceType.name)
      contextBuilder += ("exp_engagement_push", notification.experiment.name)
      contextBuilder += ("pendingNotificationCount", notification.unvisitedCount)
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.WAS_NOTIFIED, sentAt))
    }
  }

  private def sentOrgPushNotification(userId: Id[User], deviceType: DeviceType, notification: OrganizationPushNotification): Unit = {
    val sentAt = currentDateTime
    SafeFuture {
      val contextBuilder = heimdalContextBuilder()
      contextBuilder += ("action", "sent")
      contextBuilder += ("channel", push)
      contextBuilder.addNotificationCategory(NotificationCategory.User.MESSAGE)
      contextBuilder += ("global", false)
      contextBuilder += ("category", "simple")
      contextBuilder += ("subcategory", notification.category.name)
      contextBuilder += ("user", userId.id)
      contextBuilder += ("os", deviceType.name)
      contextBuilder += ("exp_engagement_push", notification.experiment.name)
      contextBuilder += ("pendingNotificationCount", notification.unvisitedCount)
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.WAS_NOTIFIED, sentAt))
    }
  }

  def sentGlobalNotification(userIds: Set[Id[User]], notificationId: ExternalId[Notification], notificationItemId: ExternalId[NotificationItem], category: NotificationCategory): Unit = {
    val sentAt = currentDateTime
    SafeFuture {
      val contextBuilder = heimdalContextBuilder()
      contextBuilder += ("action", "sent")
      contextBuilder += ("channel", kifi)
      contextBuilder.addNotificationCategory(category)
      contextBuilder += ("global", true)
      contextBuilder += ("notificationId", notificationId.id)
      contextBuilder += ("notificationItemId", notificationItemId.id)
      val context = contextBuilder.build
      userIds.foreach { id => heimdal.trackEvent(UserEvent(id, context, UserEventTypes.WAS_NOTIFIED, sentAt)) }
    }
  }

  def clearedNotification(userId: Id[User], notificationOrThreadId: Either[ExternalId[Notification], PublicId[Keep]], notificationItemOrMessageId: Either[ExternalId[NotificationItem], PublicId[Message]], existingContext: HeimdalContext): Unit = {
    val clearedAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++= existingContext.data
      contextBuilder += ("action", "cleared")
      contextBuilder += ("channel", kifi)
      notificationOrThreadId.fold(
        notificationId => contextBuilder += ("notificationId", notificationId.id),
        keepId => contextBuilder += ("threadId", keepId.id)
      )
      notificationItemOrMessageId.fold(
        notificationItemId => contextBuilder += ("notificationItemId", notificationItemId.id),
        messageId => contextBuilder += ("messageId", messageId.id)
      )
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.WAS_NOTIFIED, clearedAt))
    }
  }

  def addedParticipantsToConversation(userId: Id[User], newUserParticipants: Seq[Id[User]], newNonUserParticipants: Seq[NonUserParticipant], thread: MessageThread, source: Option[KeepEventSourceKind], existingContext: HeimdalContext) = {
    val addedAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++= existingContext.data
      contextBuilder += ("action", "addedParticipants")
      contextBuilder += ("threadId", thread.pubKeepId.id)
      if (newUserParticipants.nonEmpty) { contextBuilder += ("newUserParticipants", newUserParticipants.map(_.id)) }
      if (newNonUserParticipants.nonEmpty) { contextBuilder += ("newNonUserParticipants", newNonUserParticipants.map(_.identifier)) }
      contextBuilder += ("newParticipantKinds", newUserParticipants.map(_ => "user") ++ newNonUserParticipants.map(_.kind.name))
      contextBuilder += ("participantsAdded", newNonUserParticipants.length)
      contextBuilder += ("uriId", thread.uriId.toString)
      source.foreach { src => contextBuilder += ("source", src.value) }
      addParticipantsInfo(contextBuilder, thread.participants)
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.MESSAGED, addedAt))
    }
  }

  def sentMessage(message: ElizaMessage, thread: MessageThread, isActuallyNew: Option[Boolean], existingContext: HeimdalContext) = {
    val sentAt = message.createdAt
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++= existingContext.data
      if (message.from.asUser.safely.contains(thread.startedBy) && thread.numMessages <= 1) {
        contextBuilder += ("action", "startedConversation")
        contextBuilder += ("isNewConversation", isActuallyNew getOrElse false)
      } else {
        contextBuilder += ("action", "replied")
      }

      contextBuilder += ("threadId", thread.pubKeepId.id)
      contextBuilder += ("messageId", message.pubId.id)
      message.source.foreach { source => contextBuilder += ("source", source.value) }
      contextBuilder += ("uriId", thread.uriId.toString)
      addParticipantsInfo(contextBuilder, thread.participants)
      message.from match {
        case MessageSender.User(userId) =>
          val uriId = thread.uriId
          shoebox.getPersonalKeepRecipientsOnUris(userId, Set(uriId)).foreach { personalKeeps =>
            contextBuilder += ("isKeep", personalKeeps.getOrElse(uriId, Set.empty).exists(_.recipients.libraries.nonEmpty))
            getOrganizationsSharedByParticipants(thread.participants)
              .foreach { sharedOrgs =>
                if (sharedOrgs.nonEmpty) contextBuilder += ("allParticipantsInOrgId", Random.shuffle(sharedOrgs).head.toString)
                val context = contextBuilder.build
                heimdal.trackEvent(UserEvent(userId, context, UserEventTypes.MESSAGED, sentAt))
                heimdal.trackEvent(UserEvent(userId, context, UserEventTypes.USED_KIFI, sentAt))
                heimdal.setUserProperties(userId, "lastMessaged" -> ContextDate(sentAt))

                // Anonymized event with page information
                anonymise(contextBuilder)
                contextBuilder.addUrlInfo(thread.url)
                heimdal.trackEvent(AnonymousEvent(contextBuilder.build, AnonymousEventTypes.MESSAGED, sentAt))
              }
          }

        case MessageSender.NonUser(nonUser) => heimdal.trackEvent(NonUserEvent(nonUser.identifier, nonUser.kind, contextBuilder.build, NonUserEventTypes.MESSAGED, sentAt))

        case MessageSender.System => heimdal.trackEvent(SystemEvent(contextBuilder.build, SystemEventTypes.MESSAGED))
      }
    }
  }

  def changedMute(userId: Id[User], keepId: Id[Keep], mute: Boolean, existingContext: HeimdalContext): Unit = {
    val changedAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++= existingContext.data
      val action = if (mute) "mutedConversation" else "unmutedConversation"
      contextBuilder += ("action", action)
      contextBuilder += ("threadId", keepId.id)
      val threadOpt = db.readOnlyReplica { implicit session => threadRepo.getByKeepId(keepId) }
      threadOpt.foreach(thread => addParticipantsInfo(contextBuilder, thread.participants))
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

  private def getOrganizationsSharedByParticipants(participants: MessageThreadParticipants): Future[Set[Id[Organization]]] = {
    shoebox.getOrganizationsForUsers(participants.allUsers).map { orgsByUserId =>
      orgsByUserId.values.reduceLeftOption[Set[Id[Organization]]] { case (acc, orgSet) => acc.intersect(orgSet) }.getOrElse(Set.empty)
    }
  }
}
