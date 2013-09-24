package com.keepit.shoebox

import net.codingwell.scalaguice.ScalaModule

import com.keepit.common.mail.PlayMailerAPI
import com.typesafe.plugin.MailerAPI

case class PlayMailerModule() extends ScalaModule {
  def configure() {
    bind[MailerAPI].to[PlayMailerAPI]
  }
}
