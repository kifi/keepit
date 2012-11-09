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

object CommentController extends Controller with Logging with SecureSocial {

  def createComment(url: String, 
                    externalId: ExternalId[User], 
                    title: String = "",
                    text: String, 
                    permission: String = "", 
                    recipients: String = "") = SecuredAction(false) { request =>
    val comment = CX.withConnection { implicit conn => 
      val userId = User.getOpt(externalId).getOrElse(throw new Exception("Invalid userid"))
      val uri = NormalizedURI.getByNormalizedUrl(url) match {
        case Some(nuri) => nuri
        case None => NormalizedURI(title = title, url = url)
      }
      permission match {
        case "private" =>
          Comment(normalizedURI = uri.id.get, userId = userId.id.get, text = text, permissions = Comment.Permissions.PRIVATE).save
        case "conversation" =>
          //TODO
          Comment(normalizedURI = uri.id.get, userId = userId.id.get, text = text, permissions = Comment.Permissions.CONVERSATION).save
        case "public" | "" =>
          Comment(normalizedURI = uri.id.get, userId = userId.id.get, text = text, permissions = Comment.Permissions.PUBLIC).save
      }
    }

    Ok(JsObject(("commentId" -> JsString(comment.externalId.id)) :: Nil))

  }
  def getComments(url: String, 
                  externalId: ExternalId[User], 
                  permission: String = "") = SecuredAction(false) { request =>
    val comments = CX.withConnection { implicit conn => 
      val user = User.get(externalId)
      NormalizedURI.getByNormalizedUrl(url) match {
        case Some(normalizedURI) =>
          allComments(user.id.get, normalizedURI) map { commentGroup =>
            (commentGroup._1, commentGroup._2 map(CommentWithSocialUser(_)))
          }
        case None =>
          List[(State[Comment.Permission],Seq[CommentWithSocialUser])]()
      }
    }
    
    Ok(commentWithSocialUserSerializer.writes(comments)).as(ContentTypes.JSON)
  }
  
  private def allComments(userId: Id[User], normalizedURI: NormalizedURI)(implicit conn: Connection): List[(State[Comment.Permission],Seq[Comment])] =
    (Comment.Permissions.PUBLIC -> publicComments(normalizedURI)) :: 
    (Comment.Permissions.CONVERSATION -> conversationComments(userId, normalizedURI)) :: 
    (Comment.Permissions.PRIVATE -> privateComments(userId, normalizedURI)) :: Nil
  
  private def publicComments(normalizedURI: NormalizedURI)(implicit conn: Connection) =
    Comment.getPublicByNormalizedUri(normalizedURI.id.get)
    
  private def privateComments(userId: Id[User], normalizedURI: NormalizedURI)(implicit conn: Connection) =
    Comment.getPrivateByNormalizedUri(normalizedURI.id.get, userId)

  private def conversationComments(userId: Id[User], normalizedURI: NormalizedURI)(implicit conn: Connection) =
    Comment.getConversationsByNormalizedUri(normalizedURI.id.get, userId)

}
