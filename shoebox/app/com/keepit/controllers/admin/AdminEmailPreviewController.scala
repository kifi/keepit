package com.keepit.controllers.admin

import play.api.Play.current
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.db.Id
import com.keepit.model.{Comment, NormalizedURI, User}

object AdminEmailPreviewController extends FortyTwoController {

  val sender = User(firstName = "Jared", lastName = "Jacobs")
  val recipient = User(firstName = "Eishay", lastName = "Smith")
  val uri = NormalizedURI(title = Some("New Balance Minimus"), url = "http://www.newbalance.com/NB-Minimus/minimus,default,pg.html", urlHash = "")

  def newMessage = AdminHtmlAction { implicit request =>
    Ok(views.html.email.newMessage(sender, recipient, uri,
        Comment(normalizedURI = null, userId = null, text = "These are the running shoes I was telling you about.")))
  }

  def newMessageReply = AdminHtmlAction { implicit request =>
    Ok(views.html.email.newMessage(sender, recipient, uri,
        Comment(normalizedURI = null, userId = null, text = "These are the running shoes I was telling you about.", parent = Some(Id(1)))))
  }

}
