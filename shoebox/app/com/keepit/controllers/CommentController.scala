package com.keepit.controllers

import scala.Option.option2Iterable
import scala.math.BigDecimal.long2bigDecimal
import com.keepit.common.db.CX
import com.keepit.common.db.ExternalId
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.social.SocialGraphPlugin
import com.keepit.common.social.SocialUserRawInfoStore
import com.keepit.common.social.UserWithSocial
import com.keepit.inject.inject
import com.keepit.model.Bookmark
import com.keepit.model.NormalizedURI
import com.keepit.model.SocialConnection
import com.keepit.model.SocialUserInfo
import com.keepit.model.User
import com.keepit.search.graph.URIGraph
import com.keepit.search.index.ArticleIndexer
import com.keepit.serializer.UserWithSocialSerializer.userWithSocialSerializer
import play.api.Play.current
import play.api.http.ContentTypes
import play.api.libs.json.JsArray
import play.api.libs.json.JsNumber
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.mvc.Action
import play.api.mvc.Controller
import securesocial.core.SecureSocial
import play.api.libs.json.JsBoolean
import com.keepit.model.Comment
import java.sql.Connection
import com.keepit.common.social.CommentWithSocialUser
import com.keepit.serializer.CommentWithSocialUserSerializer._
import com.keepit.common.db.State
import securesocial.core.java.SecureSocial.SecuredAction
import com.keepit.common.controller.FortyTwoController
import com.keepit.model.CommentRecipient

object CommentController extends FortyTwoController {

  def createComment(url: String,
                    externalId: ExternalId[User],
                    text: String,
                    permission: String,
                    recipients: String = "",
                    parent: String = "") = SecuredAction(false) { request =>
    val comment = CX.withConnection { implicit conn =>
      val userId = User.getOpt(externalId).getOrElse(throw new Exception("Invalid userid"))
      val uri = NormalizedURI.getByNormalizedUrl(url) match {
        case Some(nuri) => nuri
        case None => NormalizedURI(title = "title", url = url).save
      }
      val parentIdOpt = parent match {
        case "" => None
        case p => Comment.getOpt(ExternalId[Comment](p)) match {
          case Some(p) => p.id
          case None => throw new Exception("Invalid parent provided!")
        }
      }
      permission.toLowerCase match {
        case "private" =>
          Comment(normalizedURI = uri.id.get, userId = userId.id.get, text = text, permissions = Comment.Permissions.PRIVATE, parent = parentIdOpt).save
        case "message" =>
          val newComment = Comment(normalizedURI = uri.id.get, userId = userId.id.get, text = text, permissions = Comment.Permissions.MESSAGE, parent = parentIdOpt).save
          createRecipients(newComment.id.get, recipients, parentIdOpt)
          newComment
        case "public" | "" =>
          Comment(normalizedURI = uri.id.get, userId = userId.id.get, text = text, permissions = Comment.Permissions.PUBLIC, parent = parentIdOpt).save
      }
    }

    Ok(JsObject(("commentId" -> JsString(comment.externalId.id)) :: Nil))

  }

  def getComments(url: String,
                  permission: String = "",
                  parent: Option[ExternalId[Comment]] = None) = AuthenticatedJsonAction { request =>
    val comments = CX.withConnection { implicit conn =>
      val user = User.get(request.userId)
      NormalizedURI.getByNormalizedUrl(url) match {
        case Some(normalizedURI) =>
          val comments = permission match {
            case "private" => (Comment.Permissions.PRIVATE -> privateComments(user.id.get, normalizedURI)) :: Nil
            case "public" =>  (Comment.Permissions.PUBLIC -> publicComments(normalizedURI)) :: Nil
            case "message" => (Comment.Permissions.MESSAGE -> messageComments(user.id.get, normalizedURI)) :: Nil
            case _ => allComments(user.id.get, normalizedURI)
          }

          comments map { commentGroup =>
            (commentGroup._1, commentGroup._2 map(CommentWithSocialUser(_)))
          }
        case None =>
          List[(State[Comment.Permission],Seq[CommentWithSocialUser])]()
      }
    }

    Ok(commentWithSocialUserSerializer.writes(comments)).as(ContentTypes.JSON)
  }

  def getReplies(commentId: ExternalId[Comment]) = AuthenticatedJsonAction { request =>
    val replies = CX.withConnection { implicit conn =>
      Comment.getChildren(Comment.get(commentId).id.get) map { child => CommentWithSocialUser(child) }
    }
    Ok(commentWithSocialUserSerializer.writes(replies)).as(ContentTypes.JSON)
  }


  def createRecipients(commentId: Id[Comment], recipients: String, parentIdOpt: Option[Id[Comment]])(implicit conn: Connection) = {
    recipients.split(",").map(_.trim()) map { recipientId =>
      // Split incoming list of externalIds
      User.getOpt(ExternalId[User](recipientId)) match {
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
    } flatten
  }

  private def allComments(userId: Id[User], normalizedURI: NormalizedURI)(implicit conn: Connection): List[(State[Comment.Permission],Seq[Comment])] =
    (Comment.Permissions.PUBLIC -> publicComments(normalizedURI)) ::
    (Comment.Permissions.MESSAGE -> messageComments(userId, normalizedURI)) ::
    (Comment.Permissions.PRIVATE -> privateComments(userId, normalizedURI)) :: Nil

  private def publicComments(normalizedURI: NormalizedURI, includeReplies: Boolean = false)(implicit conn: Connection) =
    Comment.getPublicByNormalizedUri(normalizedURI.id.get)

  private def privateComments(userId: Id[User], normalizedURI: NormalizedURI, includeReplies: Boolean = false)(implicit conn: Connection) =
    Comment.getPrivateByNormalizedUri(normalizedURI.id.get, userId)

  private def messageComments(userId: Id[User], normalizedURI: NormalizedURI, includeReplies: Boolean = false)(implicit conn: Connection) =
    Comment.getMessagesByNormalizedUri(normalizedURI.id.get, userId)

}
