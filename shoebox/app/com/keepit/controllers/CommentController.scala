package com.keepit.controllers

import java.sql.Connection
import scala.Option.option2Iterable
import scala.math.BigDecimal.long2bigDecimal
import play.api.Play.current
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.async.dispatch
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ElectronicMail, EmailAddresses, PostOffice}
import com.keepit.common.social.{CommentWithSocialUser, CommentWithSocialUserRepo}
import com.keepit.common.social.SocialGraphPlugin
import com.keepit.common.social.SocialUserRawInfoStore
import com.keepit.common.social.UserWithSocial
import com.keepit.common.social.UserWithSocial._
import com.keepit.inject.inject
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
import akka.util.duration._

object CommentController extends FortyTwoController {

  // TODO: Remove parameters and only check request body once all installations are 2.1.6 or later.
  def createComment(urlOpt: Option[String],
                    titleOpt: Option[String],
                    textOpt: Option[String],
                    permissionsOpt: Option[String],
                    recipientsOpt: Option[String],
                    parentOpt: Option[String]) = AuthenticatedJsonAction { request =>
    val (urlStr, title, text, permissions, recipients, parent) = request.body.asJson match {
      case Some(o) => (
        (o \ "url").as[String],
        (o \ "title") match { case JsString(s) => s; case _ => ""},
        (o \ "text").as[String].trim,
        (o \ "permissions").as[String],
        (o \ "recipients") match { case JsString(s) => s; case _ => ""},
        (o \ "parent") match { case JsString(s) => s; case _ => ""})
      case _ => (
        urlOpt.get,
        titleOpt.getOrElse(""),
        textOpt.get.trim,
        permissionsOpt.get,
        recipientsOpt.getOrElse(""),
        parentOpt.getOrElse(""))
    }


    if (text.isEmpty) throw new Exception("Empty comments are not allowed")
    val comment = CX.withConnection { implicit conn =>
      val userId = request.userId
      val uri = NormalizedURICxRepo.getByNormalizedUrl(urlStr).getOrElse(NormalizedURIFactory(url = urlStr).save)

      val parentIdOpt = parent match {
        case "" => None
        case id => CommentCxRepo.get(ExternalId[Comment](id)).id
      }

      val url: URL = URLCxRepo.get(urlStr).getOrElse(URLFactory(url = urlStr, normalizedUriId = uri.id.get).save)

      permissions.toLowerCase match {
        case "private" =>
          Comment(uriId = uri.id.get, urlId = url.id, userId = userId, pageTitle = title, text = LargeString(text), permissions = CommentPermissions.PRIVATE, parent = parentIdOpt).save
        case "message" =>
          val newComment = Comment(uriId = uri.id.get, urlId = url.id,  userId = userId, pageTitle = title, text = LargeString(text), permissions = CommentPermissions.MESSAGE, parent = parentIdOpt).save
          createRecipients(newComment.id.get, recipients, parentIdOpt)
          newComment
        case "public" | "" =>
          Comment(uriId = uri.id.get, urlId = url.id, userId = userId, pageTitle = title, text = LargeString(text), permissions = CommentPermissions.PUBLIC, parent = parentIdOpt).save
        case _ =>
          throw new Exception("Invalid comment permission")
      }
    }

    dispatch(notifyRecipients(comment), {e => log.error("Could not persist emails for comment %s".format(comment.id.get), e)})

    comment.permissions match {
      case CommentPermissions.PUBLIC =>
        Ok(JsObject(Seq("commentId" -> JsString(comment.externalId.id))))
      case CommentPermissions.MESSAGE =>
        val threadInfo = inject[DBConnection].readOnly(implicit s => inject[CommentWithSocialUserRepo].load(comment))
        Ok(JsObject(Seq("message" -> commentWithSocialUserSerializer.writes(threadInfo))))
      case _ =>
        Ok(JsObject(Seq("commentId" -> JsString(comment.externalId.id))))
    }
  }

  def getUpdates(url: String) = AuthenticatedJsonAction { request =>
    val (messageCount, privateCount, publicCount) = CX.withReadOnlyConnection { implicit conn =>
      NormalizedURICxRepo.getByNormalizedUrl(url) map {uri =>
        val uriId = uri.id.get
        val userId = request.userId
        val messageCount = CommentCxRepo.getMessagesWithChildrenCount(uriId, userId)
        val privateCount = CommentCxRepo.getPrivateCount(uriId, userId)
        val publicCount = CommentCxRepo.getPublicCount(uriId)
        (messageCount, privateCount, publicCount)
      } getOrElse (0, 0L, 0L)
    }
    Ok(JsObject(List(
        "publicCount" -> JsNumber(publicCount),
        "privateCount" -> JsNumber(privateCount),
        "messageCount" -> JsNumber(messageCount),
        "countSum" -> JsNumber(publicCount + privateCount + messageCount)
    )))
  }

  def getComments(url: String) = AuthenticatedJsonAction { request =>
    val comments = inject[DBConnection].readOnly{ implicit session =>
      inject[NormalizedURIRepo].getByNormalizedUrl(url) map { normalizedURI =>
          publicComments(normalizedURI).map(inject[CommentWithSocialUserRepo].load(_))
        } getOrElse Nil
    }
    Ok(commentWithSocialUserSerializer.writes(CommentPermissions.PUBLIC -> comments))
  }

  def getMessageThreadList(url: String) = AuthenticatedJsonAction { request =>
    val comments = CX.withConnection { implicit conn =>
      NormalizedURICxRepo.getByNormalizedUrl(url) map { normalizedURI =>
          messageComments(request.userId, normalizedURI).map(ThreadInfo(_, Some(request.userId))).reverse
        } getOrElse Nil
    }
    log.info("comments for url %s:\n%s".format(url, comments mkString "\n"))
    Ok(threadInfoSerializer.writes(CommentPermissions.MESSAGE -> comments))
  }

  def getMessageThread(commentId: ExternalId[Comment]) = AuthenticatedJsonAction { request =>
    val replies = inject[DBConnection].readOnly{ implicit session =>
      val repo = inject[CommentRepo]
      val comment = repo.get(commentId)
      val parent = comment.parent map (repo.get) getOrElse (comment)
      if (true) // TODO: hasPermission(user.id.get, comment.id.get) ???????????????
        (Seq(parent) ++ repo.getChildren(parent.id.get) map { child => inject[CommentWithSocialUserRepo].load(child) })
      else
          Nil
    }
    Ok(commentWithSocialUserSerializer.writes(CommentPermissions.MESSAGE -> replies))
  }

  // TODO: delete once no beta users have old plugin supporting replies
  def getReplies(commentId: ExternalId[Comment]) = AuthenticatedJsonAction { request =>
    val replies = inject[DBConnection].readOnly{ implicit session =>
      val repo = inject[CommentRepo]
      val comment = repo.get(commentId)
      val user = inject[UserRepo].get(request.userId)
      if (true) // TODO: hasPermission(user.id.get, comment.id.get) ??????????????
        repo.getChildren(comment.id.get) map { child => inject[CommentWithSocialUserRepo].load(child) }
      else
          Nil
    }
    Ok(commentWithSocialUserSerializer.writes(replies))
  }

  // TODO: Remove parameters and only check request body once all installations are 2.1.6 or later.
  def startFollowing(urlOpt: Option[String]) = AuthenticatedJsonAction { request =>
    val url = urlOpt.getOrElse((request.body.asJson.get \ "url").as[String])
    inject[DBConnection].readWrite { implicit session =>
      val uriRepo = inject[NormalizedURIRepo]
      val urlRepo = inject[URLRepo]
      val followRepo = inject[FollowRepo]
      val uriId = uriRepo.getByNormalizedUrl(url).getOrElse(uriRepo.save(NormalizedURIFactory(url = url))).id.get
      followRepo.get(request.userId, uriId) match {
        case Some(follow) if !follow.isActive => Some(followRepo.save(follow.activate))
        case None =>
          val urlId = urlRepo.get(url).getOrElse(urlRepo.save(URLFactory(url = url, normalizedUriId = uriId))).id
          Some(followRepo.save(Follow(userId = request.userId, urlId = urlId, uriId = uriId)))
        case _ => None
      }
    }

    Ok(JsObject(Seq("following" -> JsBoolean(true))))
  }

  // TODO: Remove parameters and only check request body once all installations are 2.1.6 or later.
  def stopFollowing(urlOpt: Option[String]) = AuthenticatedJsonAction { request =>
    val url = urlOpt.getOrElse((request.body.asJson.get \ "url").as[String])
    inject[DBConnection].readWrite { implicit session =>
      val uriRepo = inject[NormalizedURIRepo]
      val followRepo = inject[FollowRepo]
      uriRepo.getByNormalizedUrl(url) match {
        case Some(uri) => followRepo.get(request.userId, uri.id.get) match {
          case Some(follow) => Some(followRepo.save(follow.deactivate))
          case None => None
        }
        case None => None
      }
    }

    Ok(JsObject(Seq("following" -> JsBoolean(false))))
  }

  // Given a list of comma separated external user ids, side effects and creates all the necessary recipients
  // For comments with a parent comment, adds recipients to parent comment instead.
  private def createRecipients(commentId: Id[Comment], recipients: String, parentIdOpt: Option[Id[Comment]])(implicit conn: Connection) = {
    recipients.split(",").map(_.trim()) map { recipientId =>
      // Split incoming list of externalIds
      try {
        UserCxRepo.getOpt(ExternalId[User](recipientId)) match {
          case Some(recipientUser) =>
            log.info("Adding recipient %s to new comment %s".format(recipientUser.id.get, commentId))
            // When comment is a reply (has a parent), add recipient to parent if does not exist. Else, add to comment.
            parentIdOpt match {
              case Some(parentId) =>
                Some(CommentRecipient(commentId = parentId, userId = recipientUser.id).save)
              case None =>
                Some(CommentRecipient(commentId = commentId, userId = recipientUser.id).save)
            }
          case None =>
            // TODO: Add social User and email recipients as well
            log.info("Ignoring recipient %s for comment %s. User does not exist.".format(recipientId, commentId))
            None
        }
      }
      catch {
        case _ => None // It throws an exception if it fails ExternalId[User]. Just return None.
      }
    } flatten
  }

  private def publicComments(normalizedURI: NormalizedURI, includeReplies: Boolean = false)(implicit session: RSession) =
    inject[CommentRepo].getPublic(normalizedURI.id.get)

  private def messageComments(userId: Id[User], normalizedURI: NormalizedURI, includeReplies: Boolean = false)(implicit conn: Connection) =
    CommentCxRepo.getMessages(normalizedURI.id.get, userId)

  private[controllers] def notifyRecipients(comment: Comment): Unit = comment.permissions match {
      case CommentPermissions.PUBLIC =>
        CX.withConnection { implicit c =>
          val author = UserCxRepo.get(comment.userId)
          val uri = NormalizedURICxRepo.get(comment.uriId)
          val follows = FollowCxRepo.get(uri.id.get)
          for (userId <- follows.map(_.userId).toSet - comment.userId) {
            val recipient = UserCxRepo.get(userId)
            val deepLink = DeepLink(
                initatorUserId = Option(comment.userId),
                recipientUserId = Some(userId),
                uriId = Some(comment.uriId),
                urlId = comment.urlId,
                deepLocator = DeepLocator.ofComment(comment)).save
            val addrs = EmailAddressCxRepo.getByUser(userId)
            for (addr <- addrs.filter(_.verifiedAt.isDefined).headOption.orElse(addrs.headOption)) {
              inject[PostOffice].sendMail(ElectronicMail(
                  senderUserId = Option(comment.userId),
                  from = EmailAddresses.NOTIFICATIONS, fromName = Some("%s %s via Kifi".format(author.firstName, author.lastName)),
                  to = addr,
                  subject = "%s %s commented on a page you are following".format(author.firstName, author.lastName),
                  htmlBody = replaceLookHereLinks(views.html.email.newComment(author, recipient, deepLink.url, comment).body),
                  category = PostOffice.Categories.COMMENT))
            }
          }
        }
      case CommentPermissions.MESSAGE =>
        CX.withConnection { implicit c =>
          val senderId = comment.userId
          val sender = UserCxRepo.get(senderId)
          val uri = NormalizedURICxRepo.get(comment.uriId)
          val participants = CommentCxRepo.getParticipantsUserIds(comment)
          for (userId <- participants - senderId) {
            val recipient = UserCxRepo.get(userId)
            val deepLink = DeepLink(
                initatorUserId = Option(comment.userId),
                recipientUserId = Some(userId),
                uriId = Some(comment.uriId),
                urlId = comment.urlId,
                deepLocator = DeepLocator.ofMessageThread(comment)).save
            val addrs = EmailAddressCxRepo.getByUser(userId)
            for (addr <- addrs.filter(_.verifiedAt.isDefined).headOption.orElse(addrs.headOption)) {
              inject[PostOffice].sendMail(ElectronicMail(
                  senderUserId = Option(comment.userId),
                  from = EmailAddresses.NOTIFICATIONS, fromName = Some("%s %s via Kifi".format(sender.firstName, sender.lastName)),
                  to = addr,
                  subject = "%s %s sent you a message using KiFi".format(sender.firstName, sender.lastName),
                  htmlBody = replaceLookHereLinks(views.html.email.newMessage(sender, recipient, deepLink.url, comment).body),
                  category = PostOffice.Categories.MESSAGE))
            }
          }
        }
      case unsupported =>
        log.error("unsupported comment type for email %s".format(unsupported))
    }

  //e.g. [look here](x-kifi-sel:body>div#page.watch>div:nth-child(4\)>div#watch7-video-container)
  def replaceLookHereLinks(text: String): String =
    """\[((?:\\\]|[^\]])*)\]\(x-kifi-sel:(?:\\\)|[^)])*\)""".r.replaceAllIn(
        text, m => "[" + m.group(1).replaceAll("""\\(.)""", "$1") + "]")

  def followsView = AdminHtmlAction { implicit request =>
    val uriAndUsers = inject[DBConnection].readOnly { implicit s =>
      inject[FollowRepo].all() map {f => 
        (toUserWithSocial(inject[UserRepo].get(f.userId)), f, inject[NormalizedURIRepo].get(f.uriId))
      }
    }
    Ok(views.html.follows(uriAndUsers))
  }

  def commentsViewFirstPage = commentsView(0)

  def commentsView(page: Int = 0) = AdminHtmlAction { request =>
    val PAGE_SIZE = 200
    val (count, uriAndUsers) = CX.withConnection { implicit conn =>
      val comments = CommentCxRepo.page(page, PAGE_SIZE)
      val count = CommentCxRepo.count(CommentPermissions.PUBLIC)
      (count, (comments map {co => (toUserWithSocialCX(UserCxRepo.get(co.userId)), co, NormalizedURICxRepo.get(co.uriId))} ))
    }
    val pageCount: Int = (count / PAGE_SIZE + 1).toInt
    Ok(views.html.comments(uriAndUsers, page, count, pageCount))
  }

  def messagesViewFirstPage =  messagesView(0)

  def messagesView(page: Int = 0) = AdminHtmlAction { request =>
    val PAGE_SIZE = 200
    val (count, uriAndUsers) = CX.withConnection { implicit conn =>
      val messages = CommentCxRepo.page(page, PAGE_SIZE, CommentPermissions.MESSAGE)
      val count = CommentCxRepo.count(CommentPermissions.MESSAGE)
      (count, (messages map {co =>
        (toUserWithSocialCX(UserCxRepo.get(co.userId)), co, NormalizedURICxRepo.get(co.uriId), CommentRecipientCxRepo.getByComment(co.id.get) map { r => toUserWithSocialCX(UserCxRepo.get(r.userId.get)) })
      }))
    }
    val pageCount: Int = (count / PAGE_SIZE + 1).toInt
    Ok(views.html.messages(uriAndUsers, page, count, pageCount))
  }
}
