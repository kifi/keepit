package com.keepit.commanders.emails.tips

import com.google.inject.Inject
import com.keepit.common.mail.template.{ EmailToSend, TipTemplate }
import play.twirl.api.Html

import scala.concurrent.Future

class ImportGmailContacts @Inject() () extends TipTemplate {
  def render(emailToSend: EmailToSend): Future[Option[Html]] = {
    // todo(josh)
    Future.successful(None)
  }
}
