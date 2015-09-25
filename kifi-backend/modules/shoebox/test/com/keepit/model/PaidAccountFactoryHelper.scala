package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.model.PaidAccountFactory.PartialPaidAccount
import com.keepit.payments.{ PlanManagementCommander, PaidAccount, PaidAccountRepo }

object PaidAccountFactoryHelper {
  implicit class PaidAccountPersister(partialPaidAccount: PartialPaidAccount) {
    def saved(implicit injector: Injector, session: RWSession): PaidAccount = {
      val accountTemplate = injector.getInstance(classOf[PaidAccountRepo]).save(partialPaidAccount.get.copy(id = None))

      // propagate feature settings to org permissions
      val org = injector.getInstance(classOf[OrganizationRepo]).get(accountTemplate.orgId)
      injector.getInstance(classOf[PlanManagementCommander]).setAccountFeatureSettingsHelper(orgId = org.id.get, userId = org.ownerId, settings = accountTemplate.featureSettings)

      val account = accountTemplate
      account
    }
  }
}
