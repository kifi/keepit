package com.keepit.shoebox

import com.keepit.inject.AppScoped
import com.keepit.model._
import net.codingwell.scalaguice.ScalaModule

case class ShoeboxDbSequencingModule() extends ScalaModule {
  def configure {
    bind[ImageInfoSequencingPlugin].to[ImageInfoSequencingPluginImpl].in[AppScoped]
    bind[NormalizedURISequencingPlugin].to[NormalizedURISequencingPluginImpl].in[AppScoped]
    bind[UserConnectionSequencingPlugin].to[UserConnectionSequencingPluginImpl].in[AppScoped]
    bind[LibrarySequencingPlugin].to[LibrarySequencingPluginImpl].in[AppScoped]
    bind[LibraryMembershipSequencingPlugin].to[LibraryMembershipSequencingPluginImpl].in[AppScoped]
    bind[SocialUserInfoSequencingPlugin].to[SocialUserInfoSequencingPluginImpl].in[AppScoped]
  }
}
