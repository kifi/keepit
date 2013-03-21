package com.keepit.realtime

import com.keepit.model._
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.PostOffice
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.mail.EmailAddresses
import com.keepit.serializer.CommentWithSocialUserSerializer._
import com.keepit.common.social.CommentWithSocialUserRepo
import com.keepit.common.db.slick.DBSession._
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import com.keepit.common.social.BasicUser
import com.keepit.common.social.BasicUserRepo
import com.keepit.serializer.BasicUserSerializer
import com.keepit.model.UserNotificationDetails
import org.joda.time.DateTime
import com.keepit.model.UserNotificationDetails
import com.google.inject.{Inject, ImplementedBy, Singleton}

@Singleton
class UserNotifier @Inject() (
  db: Database,
  userRepo: UserRepo,
  normalizedURIRepo: NormalizedURIRepo,
  followRepo: FollowRepo,
  emailAddressRepo: EmailAddressRepo,
  deepLinkRepo: DeepLinkRepo,
  postOffice: PostOffice,
  commentWithSocialUserRepo: CommentWithSocialUserRepo,
  basicUserRepo: BasicUserRepo,
  commentRepo: CommentRepo) {

  def comment(comment: Comment): Unit = {
    // For now, we will email instantly & push streaming notifications.
    // Soon, we will email only when the user did not get the notification.

    db.readWrite { implicit s =>
      val userNotification = UserNotification()
    }
  }

  def message(message: Comment /* Can't wait for this to change! */) = {

  }

  def notifyByEmail(notify: CommentDetails)(implicit session: RSession) = {
    val recipient = userRepo.get(notify.recipient.externalId)
    val author = userRepo.get(notify.author.externalId)
    val addrs = emailAddressRepo.getByUser(recipient.id.get)
    for (addr <- addrs.filter(_.verifiedAt.isDefined).headOption.orElse(addrs.headOption)) {
      postOffice.sendMail(ElectronicMail(
          senderUserId = author.id,
          from = EmailAddresses.NOTIFICATIONS, fromName = Some("%s %s via Kifi".format(author.firstName, author.lastName)),
          to = addr,
          subject = "%s %s commented on a page you are following".format(author.firstName, author.lastName),
          htmlBody = replaceLookHereLinks(views.html.email.newComment(author, recipient, notify.url, title, text).body),
          category = PostOffice.Categories.COMMENT))
    }
  }

  def createCommentDetails(comment: Comment)(implicit session: RWSession): Set[CommentDetails] = {
    implicit val bus = BasicUserSerializer.basicUserSerializer

    val author = userRepo.get(comment.userId)
    val uri = normalizedURIRepo.get(comment.uriId)
    val follows = followRepo.getByUri(uri.id.get)
    for (userId <- follows.map(_.userId).toSet - comment.userId + comment.userId) yield {
      val recipient = userRepo.get(userId)
      val deepLink = deepLinkRepo.save(DeepLink(
          initatorUserId = Option(comment.userId),
          recipientUserId = Some(userId),
          uriId = Some(comment.uriId),
          urlId = comment.urlId,
          deepLocator = DeepLocator.ofComment(comment)))
      CommentDetails(basicUserRepo.load(comment.userId), basicUserRepo.load(userId), deepLink.url, comment.pageTitle, comment.text, comment.createdAt)
    }
  }

  def createMessageDetails(message: Comment)(implicit session: RWSession): Set[MessageDetails] = {
    implicit val bus = BasicUserSerializer.basicUserSerializer

    val author = userRepo.get(message.userId)
    val uri = normalizedURIRepo.get(message.uriId)
    val participants = commentRepo.getParticipantsUserIds(message.id.get)
    for (userId <- participants - author.id.get + author.id.get) yield {
      val recipient = userRepo.get(userId)
      val deepLink = deepLinkRepo.save(DeepLink(
          initatorUserId = Option(message.userId),
          recipientUserId = Some(userId),
          uriId = Some(message.uriId),
          urlId = message.urlId,
          deepLocator = DeepLocator.ofMessageThread(message)))
      MessageDetails(basicUserRepo.load(message.userId), basicUserRepo.load(userId), Some(deepLink.url), Some(message.pageTitle), message.text, message.createdAt)
    }
  }

  case class CommentDetails(author: BasicUser, recipient: BasicUser, url: String, title: String, text: String, createdAt: DateTime) extends UserNotificationDetails {
    implicit val bus = BasicUserSerializer.basicUserSerializer
    val payload =  Json.obj(
      "author" -> author,
      "recipient" -> recipient,
      "url" -> url,
      "title" -> title,
      "text" -> text,
      "createdAt" -> createdAt
    )
  }

  case class MessageDetails(author: BasicUser, recipient: BasicUser, url: Option[String], title: Option[String], text: String, createdAt: DateTime) extends UserNotificationDetails {
    implicit val bus = BasicUserSerializer.basicUserSerializer
    val payload =  Json.obj(
      "author" -> author,
      "recipient" -> recipient,
      "url" -> url,
      "title" -> title,
      "text" -> text,
      "createdAt" -> createdAt
    )
  }



  //e.g. [look here](x-kifi-sel:body>div#page.watch>div:nth-child(4\)>div#watch7-video-container)
  def replaceLookHereLinks(text: String): String =
    """\[((?:\\\]|[^\]])*)\]\(x-kifi-sel:(?:\\\)|[^)])*\)""".r.replaceAllIn(
        text, m => "[" + m.group(1).replaceAll("""\\(.)""", "$1") + "]")

}