package com.keepit.controllers.admin

import play.api.Play.current
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.db.Id
import com.keepit.model.{Comment, NormalizedURI, User}

object AdminEmailPreviewController extends FortyTwoController {

  val sender = User(firstName = "Jared", lastName = "Jacobs")
  val recipient = User(firstName = "Eishay", lastName = "Smith", id = Some(Id(9)))
  val uri = NormalizedURI(title = "New Balance Minimus", url = "http://www.newbalance.com/NB-Minimus/minimus,default,pg.html", urlHash = "")

  def newMessage = AdminHtmlAction { implicit request =>
    Ok(views.html.email.newMessage(sender, recipient, uri,
        Comment(normalizedURI = null, userId = null, text = "These are the running shoes I was telling you about.")))
  }

  def newMessageReply = AdminHtmlAction { implicit request =>
    Ok(views.html.email.newMessage(sender, recipient, uri,
        Comment(normalizedURI = null, userId = null, text = "These are the running shoes I was telling you about.", parent = Some(Id(1)))))
  }

  def newCommentReply = AdminHtmlAction { implicit request =>
    Ok(views.html.email.newCommentReply(sender, recipient, uri,
        Comment(normalizedURI = null, userId = recipient.id.get, text = null),
        Comment(normalizedURI = null, userId = null, text = "These are the running shoes I was telling you about.")))
  }

  def newCommentReply2 = AdminHtmlAction { implicit request =>
    Ok(views.html.email.newCommentReply(sender, recipient, uri,
        Comment(normalizedURI = null, userId = null, text = null),
        Comment(normalizedURI = null, userId = null, text = "These are the running shoes I was telling you about.")))
  }

}
