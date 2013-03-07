package com.keepit.controllers.ext

import java.sql.Connection
import scala.Option.option2Iterable
import scala.math.BigDecimal.long2bigDecimal
import play.api.Play.current
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.async.dispatch
import com.keepit.common.controller.BrowserExtensionController
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ElectronicMail, EmailAddresses, PostOffice}
import com.keepit.common.social._
import com.keepit.model._
import com.keepit.search.graph.URIGraph
import com.keepit.search.index.ArticleIndexer
import com.keepit.serializer.UserWithSocialSerializer.userWithSocialSerializer
import com.keepit.serializer.CommentWithSocialUserSerializer.commentWithSocialUserSerializer
import com.keepit.serializer.ThreadInfoSerializer.threadInfoSerializer
import play.api.http.ContentTypes
import play.api.libs.concurrent.Akka
import play.api.libs.json.{JsArray, JsBoolean, JsNumber, JsObject, JsString}
import play.api.mvc.Action
import play.api.mvc.Controller
import securesocial.core.SecureSocial
import securesocial.core.java.SecureSocial.SecuredAction
import com.keepit.common.social.ThreadInfo
import com.keepit.common.healthcheck.BabysitterTimeout
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.analytics.ActivityStream
import views.html

@Singleton
class ExtCommentController @Inject() (db: Database,
  commentRepo: CommentRepo,
  commentRecipientRepo: CommentRecipientRepo,
  normalizedURIRepo: NormalizedURIRepo,
  commentReadRepo: CommentReadRepo,
  urlRepo: URLRepo,
  userRepo: UserRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  commentWithSocialUserRepo: CommentWithSocialUserRepo,
  followRepo: FollowRepo,
  threadInfoRepo: ThreadInfoRepo,
  emailAddressRepo: EmailAddressRepo,
  deepLinkRepo: DeepLinkRepo,
  postOffice: PostOffice,
  activityStream: ActivityStream)
    extends BrowserExtensionController {

  def getCounts(ids: String) = AuthenticatedJsonAction { request =>
    val nUriExtIds = ids.split('.').map(ExternalId[NormalizedURI](_))
    val counts = db.readOnly { implicit s =>
      nUriExtIds.map { extId =>
        val id = normalizedURIRepo.get(extId).id.get
        extId -> (commentRepo.getPublicCount(id), commentRepo.getMessages(id, request.userId).size)
      }
    }
    Ok(JsObject(counts.map { case (id, n) => id.id -> JsArray(Seq(JsNumber(n._1), JsNumber(n._2))) }))
  }

  def createComment() = AuthenticatedJsonAction { request =>
    val o = request.body.asJson.get
    val (urlStr, title, text, permissions, recipients, parent) = (
      (o \ "url").as[String],
      (o \ "title") match { case JsString(s) => s; case _ => ""},
      (o \ "text").as[String].trim,
      (o \ "permissions").as[String],
      (o \ "recipients") match { case JsString(s) => s; case _ => ""},
      (o \ "parent") match { case JsString(s) => s; case _ => ""})

    if (text.isEmpty) throw new Exception("Empty comments are not allowed")
    val comment = db.readWrite {implicit s =>
      val userId = request.userId
      val uri = normalizedURIRepo.save(normalizedURIRepo.getByNormalizedUrl(urlStr).getOrElse(NormalizedURIFactory(url = urlStr)))

      val parentIdOpt = parent match {
        case "" => None
        case id => commentRepo.get(ExternalId[Comment](id)).id
      }

      val url: URL = urlRepo.save(urlRepo.get(urlStr).getOrElse(URLFactory(url = urlStr, normalizedUriId = uri.id.get)))

      permissions.toLowerCase match {
        case "private" =>
          commentRepo.save(Comment(uriId = uri.id.get, urlId = url.id, userId = userId, pageTitle = title, text = LargeString(text), permissions = CommentPermissions.PRIVATE, parent = parentIdOpt))
        case "message" =>
          val newComment = commentRepo.save(Comment(uriId = uri.id.get, urlId = url.id,  userId = userId, pageTitle = title, text = LargeString(text), permissions = CommentPermissions.MESSAGE, parent = parentIdOpt))
          createRecipients(newComment.id.get, recipients, parentIdOpt)

          val newCommentRead = commentReadRepo.getByUserAndParent(userId, parentIdOpt.getOrElse(newComment.id.get)) match {
            case Some(commentRead) => // existing CommentRead entry for this message thread
              assert(commentRead.parentId.isDefined)
              commentRead.withLastReadId(newComment.id.get)
            case None =>
              CommentRead(userId = userId, uriId = uri.id.get, parentId = Some(parentIdOpt.getOrElse(newComment.id.get)), lastReadId = newComment.id.get)
          }
          commentReadRepo.save(newCommentRead)
          newComment
        case "public" | "" =>
          val newComment = commentRepo.save(Comment(uriId = uri.id.get, urlId = url.id, userId = userId, pageTitle = title, text = LargeString(text), permissions = CommentPermissions.PUBLIC, parent = None))
          commentReadRepo.save(commentReadRepo.getByUserAndUri(userId, uri.id.get) match {
            case Some(commentRead) => // existing CommentRead entry for this message thread
              commentRead.withLastReadId(newComment.id.get)
            case None =>
              CommentRead(userId = userId, uriId = uri.id.get, lastReadId = newComment.id.get)
          })
          newComment
        case _ =>
          throw new Exception("Invalid comment permission")
      }
    }

    dispatch(notifyRecipients(comment), {e => log.error("Could not persist emails for comment %s".format(comment.id.get), e)})

    addToActivityStream(comment)

    comment.permissions match {
      case CommentPermissions.PUBLIC =>
        Ok(JsObject(Seq("commentId" -> JsString(comment.externalId.id))))
      case CommentPermissions.MESSAGE =>
        val threadInfo = db.readOnly(implicit s => commentWithSocialUserRepo.load(comment))
        Ok(JsObject(Seq("message" -> commentWithSocialUserSerializer.writes(threadInfo))))
      case _ =>
        Ok(JsObject(Seq("commentId" -> JsString(comment.externalId.id))))
    }
  }

  def getUpdates(url: String) = AuthenticatedJsonAction { request =>
    val (messageCount, publicCount) = db.readOnly { implicit s =>

      normalizedURIRepo.getByNormalizedUrl(url) map {uri =>
        val uriId = uri.id.get
        val userId = request.userId

        val messageCount = commentRepo.getMessagesWithChildrenCount(uriId, userId)
        val publicCount = commentRepo.getPublicCount(uriId)

        (messageCount, publicCount)
      } getOrElse (0, 0)
    }

    Ok(JsObject(List(
        "publicCount" -> JsNumber(publicCount),
        "messageCount" -> JsNumber(messageCount),
        "countSum" -> JsNumber(publicCount + messageCount)
    )))
  }

  def getComments(url: String) = AuthenticatedJsonAction { request =>
    val (comments, commentRead) = db.readOnly { implicit session =>
      val normUriOpt = normalizedURIRepo.getByNormalizedUrl(url)

      val comments = normUriOpt map { normalizedURI =>
          publicComments(normalizedURI).map(commentWithSocialUserRepo.load(_))
        } getOrElse Nil

      val lastCommentId = comments match {
        case Nil => None
        case commentList => Some(commentList.map(_.comment.id.get).maxBy(_.id))
      }

      val commentRead = normUriOpt.flatMap { normUri =>
        lastCommentId.map { lastCom =>
          commentReadRepo.getByUserAndUri(request.userId, normUri.id.get) match {
            case Some(commentRead) => // existing CommentRead entry for this user/url
              if (commentRead.lastReadId.id < lastCom.id) Some(commentRead.withLastReadId(lastCom))
              else None
            case None =>
              Some(CommentRead(userId = request.userId, uriId = normUri.id.get, lastReadId = lastCom))
          }
        }
      }
      (comments, commentRead.flatten)
    }

    commentRead.map { cr =>
      db.readWrite { implicit session =>
        commentReadRepo.save(cr)
      }
    }

    Ok(commentWithSocialUserSerializer.writes(CommentPermissions.PUBLIC -> comments))
  }

  def getMessageThreadList(url: String) = AuthenticatedJsonAction { request =>
    val comments = db.readOnly { implicit s =>
      normalizedURIRepo.getByNormalizedUrl(url) map { normalizedURI =>
          messageComments(request.userId, normalizedURI).map(threadInfoRepo.load(_, Some(request.userId))).reverse
        } getOrElse Nil
    }
    log.info("comments for url %s:\n%s".format(url, comments mkString "\n"))
    Ok(threadInfoSerializer.writes(CommentPermissions.MESSAGE -> comments))
  }

  def getMessageThread(commentId: ExternalId[Comment]) = AuthenticatedJsonAction { request =>
    val (messages, commentReadToSave) = db.readOnly{ implicit session =>
      val comment = commentRepo.get(commentId)
      val parent = comment.parent.map(commentRepo.get).getOrElse(comment)

      val messages: Seq[CommentWithSocialUser] = parent +: commentRepo.getChildren(parent.id.get) map { msg =>
        commentWithSocialUserRepo.load(msg)
      }

      // mark latest message as read for viewer

      val lastMessageId = messages.map(_.comment.id.get).maxBy(_.id)

      val commentReadToSave = commentReadRepo.getByUserAndParent(request.userId, parent.id.get) match {
        case Some(cr) if cr.lastReadId == lastMessageId =>
          None
        case Some(cr) =>
          Some(cr.withLastReadId(lastMessageId))
        case None =>
          Some(CommentRead(userId = request.userId, uriId = parent.uriId, parentId = parent.id, lastReadId = lastMessageId))
      }
      (messages, commentReadToSave)
    }

    commentReadToSave.map { cr =>
      db.readWrite{ implicit session =>
        commentReadRepo.save(cr)
      }
    }

    Ok(commentWithSocialUserSerializer.writes(CommentPermissions.MESSAGE -> messages))
  }

  def startFollowing() = AuthenticatedJsonAction { request =>
    val url = (request.body.asJson.get \ "url").as[String]
    db.readWrite { implicit session =>
      val uriId = normalizedURIRepo.getByNormalizedUrl(url).getOrElse(normalizedURIRepo.save(NormalizedURIFactory(url = url))).id.get
      followRepo.get(request.userId, uriId, excludeState = None) match {
        case Some(follow) if !follow.isActive =>
          Some(followRepo.save(follow.activate))
        case None =>
          val urlId = urlRepo.get(url).getOrElse(urlRepo.save(URLFactory(url = url, normalizedUriId = uriId))).id
          Some(followRepo.save(Follow(userId = request.userId, urlId = urlId, uriId = uriId)))
        case _ => None
      }
    }

    Ok(JsObject(Seq("following" -> JsBoolean(true))))
  }

  def stopFollowing() = AuthenticatedJsonAction { request =>
    val url = (request.body.asJson.get \ "url").as[String]
    db.readWrite { implicit session =>
      normalizedURIRepo.getByNormalizedUrl(url).map { uri =>
        followRepo.get(request.userId, uri.id.get).map { follow =>
          followRepo.save(follow.deactivate)
        }
      }
    }

    Ok(JsObject(Seq("following" -> JsBoolean(false))))
  }

  // Given a list of comma separated external user ids, side effects and creates all the necessary recipients
  // For comments with a parent comment, adds recipients to parent comment instead.
  private def createRecipients(commentId: Id[Comment], recipients: String, parentIdOpt: Option[Id[Comment]])(implicit session: RWSession) = {
    recipients.split(",").map(_.trim()).map { recipientId =>
      // Split incoming list of externalIds
      try {
        userRepo.getOpt(ExternalId[User](recipientId)) match {
          case Some(recipientUser) =>
            log.info("Adding recipient %s to new comment %s".format(recipientUser.id.get, commentId))
            // When comment is a reply (has a parent), add recipient to parent if does not exist. Else, add to comment.
            parentIdOpt match {
              case Some(parentId) =>
                Some(commentRecipientRepo.save(CommentRecipient(commentId = parentId, userId = recipientUser.id)))
              case None =>
                Some(commentRecipientRepo.save(CommentRecipient(commentId = commentId, userId = recipientUser.id)))
            }
          case None =>
            // TODO: Add social User and email recipients as well
            log.info("Ignoring recipient %s for comment %s. User does not exist.".format(recipientId, commentId))
            None
        }
      }
      catch {
        case e: Throwable => None // It throws an exception if it fails ExternalId[User]. Just return None.
      }
    } flatten
  }

  private def publicComments(normalizedURI: NormalizedURI, includeReplies: Boolean = false)(implicit session: RSession) =
    commentRepo.getPublic(normalizedURI.id.get)

  private def messageComments(userId: Id[User], normalizedURI: NormalizedURI, includeReplies: Boolean = false)(implicit session: RSession) =
    commentRepo.getMessages(normalizedURI.id.get, userId)

  private[controllers] def notifyRecipients(comment: Comment): Unit = {
    comment.permissions match {
      case CommentPermissions.PUBLIC =>
        db.readWrite { implicit s =>
          val author = userRepo.get(comment.userId)
          val uri = normalizedURIRepo.get(comment.uriId)
          val follows = followRepo.getByUri(uri.id.get)
          for (userId <- follows.map(_.userId).toSet - comment.userId) {
            val recipient = userRepo.get(userId)
            val deepLink = deepLinkRepo.save(DeepLink(
                initatorUserId = Option(comment.userId),
                recipientUserId = Some(userId),
                uriId = Some(comment.uriId),
                urlId = comment.urlId,
                deepLocator = DeepLocator.ofComment(comment)))
            val addrs = emailAddressRepo.getByUser(userId)
            for (addr <- addrs.filter(_.verifiedAt.isDefined).headOption.orElse(addrs.headOption)) {
              postOffice.sendMail(ElectronicMail(
                  senderUserId = Option(comment.userId),
                  from = EmailAddresses.NOTIFICATIONS, fromName = Some("%s %s via Kifi".format(author.firstName, author.lastName)),
                  to = addr,
                  subject = "%s %s commented on a page you are following".format(author.firstName, author.lastName),
                  htmlBody = replaceLookHereLinks(html.email.newComment(author, recipient, deepLink.url, comment).body),
                  category = PostOffice.Categories.COMMENT))
            }
          }
        }
      case CommentPermissions.MESSAGE =>
        db.readWrite { implicit s =>
          val senderId = comment.userId
          val sender = userRepo.get(senderId)
          val uri = normalizedURIRepo.get(comment.uriId)
          val participants = commentRepo.getParticipantsUserIds(comment.id.get)
          for (userId <- participants - senderId) {
            val recipient = userRepo.get(userId)
            val deepLink = deepLinkRepo.save(DeepLink(
                initatorUserId = Option(comment.userId),
                recipientUserId = Some(userId),
                uriId = Some(comment.uriId),
                urlId = comment.urlId,
                deepLocator = DeepLocator.ofMessageThread(comment)))
            val addrs = emailAddressRepo.getByUser(userId)
            for (addr <- addrs.filter(_.verifiedAt.isDefined).headOption.orElse(addrs.headOption)) {
              postOffice.sendMail(ElectronicMail(
                  senderUserId = Option(comment.userId),
                  from = EmailAddresses.NOTIFICATIONS, fromName = Some("%s %s via Kifi".format(sender.firstName, sender.lastName)),
                  to = addr,
                  subject = "%s %s sent you a message using KiFi".format(sender.firstName, sender.lastName),
                  htmlBody = replaceLookHereLinks(html.email.newMessage(sender, recipient, deepLink.url, comment).body),
                  category = PostOffice.Categories.MESSAGE))
            }
          }
        }
      case unsupported =>
        log.error("unsupported comment type for email %s".format(unsupported))
    }
  }

  //e.g. [look here](x-kifi-sel:body>div#page.watch>div:nth-child(4\)>div#watch7-video-container)
  def replaceLookHereLinks(text: String): String =
    """\[((?:\\\]|[^\]])*)\]\(x-kifi-sel:(?:\\\)|[^)])*\)""".r.replaceAllIn(
        text, m => "[" + m.group(1).replaceAll("""\\(.)""", "$1") + "]")

  private def addToActivityStream(comment: Comment) = {
    val (user, social, uri) = db.readOnly { implicit session =>
      val user = userRepo.get(comment.userId)
      val social = socialUserInfoRepo.getByUser(user.id.get).headOption.map(_.socialId.id).getOrElse("")
      val uri = normalizedURIRepo.get(comment.uriId)

      (user, social, uri)
    }

    val json = Json.obj(
      "user" -> Json.obj(
        "id" -> user.id.get.id,
        "name" -> s"${user.firstName} ${user.lastName}",
        "avatar" -> s"https://graph.facebook.com/${social}/picture?height=150&width=150"),
      "comment" -> Json.obj(
        "id" -> comment.id.get.id,
        "text" -> comment.text.toString,
        "title" -> comment.pageTitle,
        "uri" -> uri.url)
    )

    val kind = comment.permissions match {
      case CommentPermissions.PUBLIC => "comment"
      case CommentPermissions.MESSAGE => "message"
    }

    activityStream.streamActivity(kind, json)
  }
}
