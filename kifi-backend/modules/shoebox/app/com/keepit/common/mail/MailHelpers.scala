package com.keepit.common.mail

import com.keepit.commanders.Hashtags
import com.keepit.common.util.DescriptionElements
import play.twirl.api.Html

object MailHelpers {

  def textWithTags(text: String) = DescriptionElements.formatAsHtml(Hashtags.format(text))

}
