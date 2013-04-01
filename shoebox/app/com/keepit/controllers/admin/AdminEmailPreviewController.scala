package com.keepit.controllers.admin

import play.api.Play.current
import com.keepit.common.db.Id
import com.keepit.common.db.LargeString._
import com.keepit.model.{Comment, NormalizedURI, User}
import views.html

import com.keepit.common.controller.AdminController
import com.google.inject.{Inject, Singleton, Provider}

@Singleton
class AdminEmailPreviewController @Inject() ()
    extends AdminController {

  val sender = User(firstName = "Jared", lastName = "Jacobs")
  val recipient = User(firstName = "Eishay", lastName = "Smith")
  val uri = NormalizedURI(title = Some("New Balance Minimus"), url = "http://www.newbalance.com/NB-Minimus/minimus,default,pg.html", urlHash = "")

  def newMessage = AdminHtmlAction { implicit request =>
    val comment = Comment(uriId = null, userId = null, pageTitle = "my title", text = "These are the running shoes I was telling you about.")
    Ok(html.email.newMessage(sender, recipient, uri.url,
        comment.pageTitle, comment.text, comment.parent.isDefined))
  }

  def newMessageReply = AdminHtmlAction { implicit request =>
    val comment = Comment(uriId = null, userId = null, pageTitle = "my title", text = "These are the running shoes I was telling you about.", parent = Some(Id(1)))
    Ok(html.email.newMessage(sender, recipient, uri.url, comment.pageTitle, comment.text, comment.parent.isDefined))
  }

  def newComment = AdminHtmlAction { implicit request =>
    val comment = Comment(uriId = null, userId = null, pageTitle = "my title", text = "Best running shoes I've ever tried!")
    Ok(html.email.newComment(sender, recipient, uri.url,
        comment.pageTitle, comment.text))
  }

}
