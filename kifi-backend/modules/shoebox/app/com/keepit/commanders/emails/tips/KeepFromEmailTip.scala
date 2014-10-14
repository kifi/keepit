package com.keepit.commanders.emails.tips

import com.google.inject.Inject
import com.keepit.common.mail.template.EmailToSend
import play.twirl.api.Html

import scala.concurrent.Future

class KeepFromEmailTip @Inject() () {
  def render(emailToSend: EmailToSend): Future[Option[Html]] = {
    Future.successful {
      Some(views.html.email.tips.keepFromEmail())
    }
  }
}
