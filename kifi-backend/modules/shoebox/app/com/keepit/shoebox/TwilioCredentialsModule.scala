package com.keepit.shoebox

import com.keepit.commanders.{ PhoneNumber, TwilioCredentials }
import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provides, Singleton }

trait TwilioCredentialsModule extends ScalaModule

case class ProdTwilioCredentialsModule() extends TwilioCredentialsModule {
  def configure() {}

  @Singleton
  @Provides
  def twilioCredentials(): TwilioCredentials = {
    TwilioCredentials("AC5f673e4da699164a4f0f50cbfed17cd0", "d3adeac93f03603d987637470ae6eed4", PhoneNumber("+17728795434"))
  }
}

case class DevTwilioCredentialsModule() extends TwilioCredentialsModule {
  def configure() {}

  @Singleton
  @Provides
  def twilioCredentials(): TwilioCredentials = {
    TwilioCredentials("AC9943c9cdaab3efb186c04ac409b16131", "d2dc5b1a86126d37b8023124e41122ec", PhoneNumber("+15005550006"))
  }
}

