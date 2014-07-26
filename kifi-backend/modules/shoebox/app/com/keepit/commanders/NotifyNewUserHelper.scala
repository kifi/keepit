package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.store.S3ImageStore
import com.keepit.eliza.ElizaServiceClient
import com.keepit.eliza.model.MessageHandle
import com.keepit.model.{ NotificationCategory, User }

import scala.concurrent.Future

trait NotifyNewUserHelper {
  val s3ImageStore: S3ImageStore
  val elizaServiceClient: ElizaServiceClient

  def send(
    newUser: User,
    toNotify: Set[Id[User]],
    title: String,
    body: String,
    linkText: String,
    linkUrl: String,
    category: NotificationCategory,
    isSticky: Boolean = false): Future[Id[MessageHandle]] = {
    elizaServiceClient.sendGlobalNotification(
      userIds = toNotify,
      title = title,
      body = body,
      linkText = linkText,
      linkUrl = linkUrl,
      imageUrl = s3ImageStore.avatarUrlByUser(newUser),
      sticky = isSticky,
      category = category)
  }
}

class NotifyNewUserFriendsHelper @Inject() (
    val s3ImageStore: S3ImageStore,
    val elizaServiceClient: ElizaServiceClient) extends NotifyNewUserHelper {

  def apply(newUser: User, toNotify: Set[Id[User]]): Future[Id[MessageHandle]] =
    send(
      newUser = newUser,
      toNotify = toNotify,
      title = s"${newUser.firstName} ${newUser.lastName} joined Kifi!",
      body = s"Enjoy ${newUser.firstName}'s keeps in your search results and message ${newUser.firstName} directly. Invite friends to join Kifi.",
      linkText = "Invite more friends to Kifi.",
      linkUrl = "https://www.kifi.com/friends/invite",
      category = NotificationCategory.User.FRIEND_JOINED)
}

class NotifyNewUserContactsHelper @Inject() (
    val s3ImageStore: S3ImageStore,
    val elizaServiceClient: ElizaServiceClient) extends NotifyNewUserHelper {

  def apply(newUser: User, toNotify: Set[Id[User]]): Future[Id[MessageHandle]] =
    send(
      newUser = newUser,
      toNotify = toNotify,
      title = s"${newUser.firstName} ${newUser.lastName} joined Kifi!",
      body = s"To discover ${newUser.firstName}’s public keeps while searching, get connected!",
      linkText = s"Click this to send a friend request to ${newUser.firstName}.",
      linkUrl = "https://www.kifi.com/friends/invite",
      category = NotificationCategory.User.CONTACT_JOINED)
}
