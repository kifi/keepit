package com.keepit.payments

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import com.keepit.controllers.admin.{ AdminAccountView, PlanEnrollmentHistory, IncomeHistory, AdminPaymentsDashboard }
import com.keepit.model.OrganizationRepo

import scala.collection.mutable

@ImplementedBy(classOf[PaymentsDashboardCommanderImpl])
trait PaymentsDashboardCommander {
  def generateDashboard(): AdminPaymentsDashboard
}

@Singleton
class PaymentsDashboardCommanderImpl @Inject() (
    db: Database,
    orgRepo: OrganizationRepo,
    paidPlanRepo: PaidPlanRepo,
    paidAccountRepo: PaidAccountRepo,
    accountEventRepo: AccountEventRepo,
    clock: Clock) extends PaymentsDashboardCommander {
  private def createAdminAccountView(account: PaidAccount)(implicit session: RSession): AdminAccountView = {
    AdminAccountView(
      organization = orgRepo.get(account.orgId)
    )
  }

  def generateDashboard(): AdminPaymentsDashboard = db.readOnlyMaster { implicit session =>
    val now = clock.now
    val frozenAccounts = paidAccountRepo.all.filter(a => a.isActive && a.frozen).take(100).map(createAdminAccountView) // God help us if we have more than 100 frozen accounts
    val failedAccounts = paidAccountRepo.all.filter(a => a.isActive && a.paymentStatus == PaymentStatus.Failed).take(100).map(createAdminAccountView) // God help us if we have more than 100 failed accounts
    val plans = paidPlanRepo.all.filter(_.isActive)
    val curPlanEnrollments = {
      val planEnrollmentById = paidAccountRepo.getPlanEnrollments
      plans.map { p => p -> planEnrollmentById.getOrElse(p.id.get, PlanEnrollment.empty) }
    }.toMap
    val oldPlanEnrollments = {
      // mutable map logic ahoy
      val m_planByAccount = mutable.Map(paidAccountRepo.all.map(a => a.id.get -> Option(a.planId)): _*)
      val m_usersByAccount = mutable.Map(paidAccountRepo.all.map(a => a.id.get -> a.activeUsers): _*)
      accountEventRepo.adminGetByKindSince(AccountEventKind.all, now.minusWeeks(1)).map(e => (e.accountId, e.action)).foreach {
        case (accountId, AccountEventAction.OrganizationCreated(initialPlan, _)) =>
          m_planByAccount(accountId) = None
        case (accountId, AccountEventAction.PlanChanged(oldPlan, newPlan, _)) =>
          m_planByAccount(accountId) = Some(oldPlan)
        case (accountId, AccountEventAction.UserJoinedOrganization(_, _)) =>
          m_usersByAccount(accountId) -= 1
        case (accountId, AccountEventAction.UserLeftOrganization(_, _)) =>
          m_usersByAccount(accountId) += 1
        case _ =>
      }
      plans.map { p =>
        p -> PlanEnrollment(
          numAccounts = m_planByAccount.collect { case (accountId, planId) if planId.contains(p.id.get) => 1 }.sum,
          numActiveUsers = m_usersByAccount.collect { case (accountId, numUsers) if m_planByAccount(accountId).contains(p.id.get) => numUsers }.sum
        )
      }.toMap
    }
    val planEnrollments = plans.map { p => p -> PlanEnrollmentHistory(cur = curPlanEnrollments(p), old = oldPlanEnrollments(p)) }.toMap
    val totalIncome = {
      val (old, cur) = plans.map { plan =>
        val amortizedIncomePerUser = DollarAmount.cents(plan.pricePerCyclePerUser.toCents / plan.billingCycle.months)
        (amortizedIncomePerUser * planEnrollments(plan).old.numActiveUsers, amortizedIncomePerUser * planEnrollments(plan).cur.numActiveUsers)
      }.unzip
      IncomeHistory(old.reduce(_ + _), cur.reduce(_ + _))
    }
    AdminPaymentsDashboard(
      totalAmortizedIncomePerMonth = totalIncome,
      planEnrollment = planEnrollments,
      failedAccounts = failedAccounts,
      frozenAccounts = frozenAccounts
    )
  }
}
