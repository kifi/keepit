package com.keepit.shoebox

import com.keepit.inject.AppScoped
import com.keepit.model._
import net.codingwell.scalaguice.ScalaModule

case class ShoeboxDbSequencingModule() extends ScalaModule {
  def configure {
    bind[NormalizedURISequencingPlugin].to[NormalizedURISequencingPluginImpl].in[AppScoped]
    bind[UserConnectionSequencingPlugin].to[UserConnectionSequencingPluginImpl].in[AppScoped]
    bind[LibrarySequencingPlugin].to[LibrarySequencingPluginImpl].in[AppScoped]
    bind[LibraryMembershipSequencingPlugin].to[LibraryMembershipSequencingPluginImpl].in[AppScoped]
    bind[SocialConnectionSequencingPlugin].to[SocialConnectionSequencingPluginImpl].in[AppScoped]
    bind[ChangedURISeqPlugin].to[ChangedURISeqPluginImpl].in[AppScoped]
    bind[InvitationSequencingPlugin].to[InvitationSequencingPluginImpl].in[AppScoped]
    bind[CollectionSeqPlugin].to[CollectionSeqPluginImpl].in[AppScoped]
    bind[SocialUserInfoSequencingPlugin].to[SocialUserInfoSequencingPluginImpl].in[AppScoped]
    bind[UserSeqPlugin].to[UserSeqPluginImpl].in[AppScoped]
    bind[PhraseSequencingPlugin].to[PhraseSequencingPluginImpl].in[AppScoped]
    bind[UserEmailAddressSeqPlugin].to[UserEmailAddressSeqPluginImpl].in[AppScoped]
    bind[KeepSequencingPlugin].to[KeepSequencingPluginImpl].in[AppScoped]
    bind[UserIpAddressSequencingPlugin].to[UserIpAddressSequencingPluginImpl].in[AppScoped]
    bind[OrganizationSequencingPlugin].to[OrganizationSequencingPluginImpl].in[AppScoped]
    bind[OrganizationMembershipSequencingPlugin].to[OrganizationMembershipSequencingPluginImpl].in[AppScoped]
    bind[OrganizationMembershipCandidateSequencingPlugin].to[OrganizationMembershipCandidateSequencingPluginImpl].in[AppScoped]
    bind[OrganizationDomainOwnershipSequencingPlugin].to[OrganizationDomainOwnershipSequencingPluginImpl].in[AppScoped]
  }
}
