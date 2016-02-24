package com.keepit.model

import com.google.inject.Injector
import com.keepit.commanders.OrganizationCommander
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.model.PaidAccountFactory.PartialPaidAccount
import com.keepit.payments.{ PaidAccount, PaidAccountRepo, PaidPlanRepo }

object PaidAccountFactoryHelper {
  implicit class PaidAccountPersister(partialPaidAccount: PartialPaidAccount) {
    def saved(implicit injector: Injector, session: RWSession): PaidAccount = {
      val account = injector.getInstance(classOf[PaidAccountRepo]).save(partialPaidAccount.get.copy(id = None))

      // propagate feature settings to org permissions
      val org = injector.getInstance(classOf[OrganizationRepo]).get(account.orgId)
      val settings = injector.getInstance(classOf[PaidPlanRepo]).get(account.planId).defaultSettings
      injector.getInstance(classOf[OrganizationCommander]).unsafeSetAccountFeatureSettings(orgId = org.id.get, settings = settings, requesterIdOpt = None)
      account
    }
  }
}
