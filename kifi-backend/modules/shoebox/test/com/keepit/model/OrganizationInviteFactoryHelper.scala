package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.model.OrganizationInviteFactory.PartialOrganizationInvite

object OrganizationInviteFactoryHelper {
  implicit class OrganizationInvitePersister(partialOrgInvite: PartialOrganizationInvite) {
    def saved(implicit injector: Injector, session: RWSession): OrganizationInvite = {
      injector.getInstance(classOf[OrganizationInviteRepo]).save(partialOrgInvite.get.copy(id = None))
    }
  }
}
