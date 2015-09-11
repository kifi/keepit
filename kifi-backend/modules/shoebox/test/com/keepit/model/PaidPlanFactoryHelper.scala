package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.model.PaidPlanFactory.PartialPaidPlan
import com.keepit.payments.{ PaidPlan, PaidPlanRepo }

object PaidPlanFactoryHelper {
  implicit class PaidPlanPersister(partialPaidPlan: PartialPaidPlan) {
    def saved(implicit injector: Injector, session: RWSession): PaidPlan = {
      val planTemplate = injector.getInstance(classOf[PaidPlanRepo]).save(partialPaidPlan.get.copy(id = None))
      val plan = planTemplate
      plan
    }
  }
}
