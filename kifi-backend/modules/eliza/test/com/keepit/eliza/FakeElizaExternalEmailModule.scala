package com.keepit.eliza

import com.keepit.eliza.mail.MailDiscussionServerSettings
import com.google.inject.{ Provides, Singleton }

case class FakeElizaExternalEmailModule() extends ElizaExternalEmailModule {

  def configure {}

  @Singleton
  @Provides
  def mailNotificationsServerSettings: MailDiscussionServerSettings = MailDiscussionServerSettings(
    "testaddress",
    "kifi.com",
    "thesecretpassword",
    "imap.kifi.com",
    "imaps"
  )
}
