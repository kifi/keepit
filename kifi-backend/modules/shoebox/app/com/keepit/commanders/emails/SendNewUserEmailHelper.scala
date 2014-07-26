package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ LargeString, Id }
import com.keepit.common.mail.{ LocalPostOffice, SystemEmailAddress, ElectronicMail, EmailAddress }
import com.keepit.common.store.S3ImageStore
import com.keepit.model.{ UserRepo, UserEmailAddressRepo, NotificationCategory, User }

trait SendNewUserEmailHelper {
  val userRepo: UserRepo
  val postOffice: LocalPostOffice
  val emailOptOutCommander: EmailOptOutCommander
  val emailRepo: UserEmailAddressRepo
  val s3ImageStore: S3ImageStore
  val db: Database

  trait NewUserEmailSender {
    val newUser: User
    val toNotify: Set[Id[User]]
    val category: NotificationCategory
    val subject: String
    lazy val imageUrl: String = s3ImageStore.avatarUrlByUser(newUser)

    def toEmailAddress(user: User) = List(id2Email(user.id.get))

    lazy val id2Email: Map[Id[User], EmailAddress] = {
      db.readOnlyReplica { implicit session =>
        toNotify.map { userId => (userId, emailRepo.getByUser(userId)) }.toMap
      }
    }

    def unsubLink(user: User) =
      s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(emailOptOutCommander.generateOptOutToken(id2Email(user.id.get)))}"

    def htmlBody(user: User): LargeString

    def textBody(user: User): Option[LargeString]

    def send =
      toNotify.foreach { userId =>
        db.readWrite { implicit rw =>
          val user = userRepo.get(userId)
          postOffice.sendMail(ElectronicMail(
            senderUserId = None,
            from = SystemEmailAddress.NOTIFICATIONS,
            fromName = Some(s"${newUser.firstName} ${newUser.lastName} (via Kifi)"),
            to = toEmailAddress(user),
            subject = subject,
            htmlBody = htmlBody(user),
            textBody = textBody(user),
            category = category)
          )
        }
      }
  }
}

class SendNewUserEmailToFriendsHelper @Inject() (
    val userRepo: UserRepo,
    val postOffice: LocalPostOffice,
    val emailOptOutCommander: EmailOptOutCommander,
    val emailRepo: UserEmailAddressRepo,
    val s3ImageStore: S3ImageStore)(implicit val db: Database) extends SendNewUserEmailHelper {

  case class Sender(newUser: User, toNotify: Set[Id[User]]) extends NewUserEmailSender {
    val category: NotificationCategory = NotificationCategory.User.FRIEND_JOINED
    val subject = s"${newUser.firstName} ${newUser.lastName} joined Kifi"

    def htmlBody(user: User): LargeString =
      views.html.email.friendJoinedInlined(user.firstName, newUser.firstName, newUser.lastName, imageUrl, unsubLink(user)).body

    def textBody(user: User): Option[LargeString] =
      Some(views.html.email.friendJoinedText(user.firstName, newUser.firstName, newUser.lastName, imageUrl, unsubLink(user)).body)
  }

  def apply(newUser: User, toNotify: Set[Id[User]]) = Sender(newUser, toNotify).send
}

case class SendNewUserEmailToContactsHelper @Inject() (
    userRepo: UserRepo,
    postOffice: LocalPostOffice,
    emailOptOutCommander: EmailOptOutCommander,
    emailRepo: UserEmailAddressRepo,
    s3ImageStore: S3ImageStore)(implicit val db: Database) extends SendNewUserEmailHelper {

  case class Sender(newUser: User, toNotify: Set[Id[User]]) extends NewUserEmailSender {
    val category = NotificationCategory.User.CONTACT_JOINED
    val subject = s"${newUser.firstName} ${newUser.lastName} joined Kifi. Want to connect?"

    def htmlBody(user: User): LargeString =
      views.html.email.contactJoinedInlined(user.firstName, newUser.firstName, newUser.lastName, imageUrl, unsubLink(user)).body

    def textBody(user: User): Option[LargeString] =
      Some(views.html.email.contactJoinedText(user.firstName, newUser.firstName, newUser.lastName, imageUrl, unsubLink(user)).body)
  }

  def apply(newUser: User, toNotify: Set[Id[User]]) = Sender(newUser, toNotify).send
}
