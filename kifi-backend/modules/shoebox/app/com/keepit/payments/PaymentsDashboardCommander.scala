package com.keepit.payments

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import com.keepit.controllers.admin.AdminAccountView
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
    val reverseChronologicalEvents = accountEventRepo.adminGetByKindAndDate(AccountEventKind.all, start = now.minusWeeks(1), end = now).toList.sortBy(e => (e.eventTime.getMillis, e.id.get.id)).reverse

    val frozenAccounts = paidAccountRepo.all.filter(a => a.isActive && a.frozen).take(100).map(createAdminAccountView) // God help us if we have more than 100 frozen accounts
    val failedAccounts = paidAccountRepo.all.filter(a => a.isActive && a.paymentStatus == PaymentStatus.Failed).take(100).map(createAdminAccountView) // God help us if we have more than 100 failed accounts

    val (creditChanges, chargesMade) = {
      val reallyOldEvents = accountEventRepo.adminGetByKindAndDate(AccountEventKind.all, start = now.minusWeeks(2), end = now.minusWeeks(1)).toList.sortBy(e => (e.eventTime.getMillis, e.id.get.id)).reverse
      val creditChanges = CreditChangeHistory(
        old = reallyOldEvents.collect { case e if e.creditChange < DollarAmount.ZERO => e.creditChange }.sum,
        cur = reverseChronologicalEvents.collect { case e if e.creditChange < DollarAmount.ZERO => e.creditChange }.sum
      )
      val chargesMade = ChargesMadeHistory(
        old = reallyOldEvents.flatMap(_.paymentCharge).sum,
        cur = reverseChronologicalEvents.flatMap(_.paymentCharge).sum
      )
      (creditChanges, chargesMade)
    }

    val plans = paidPlanRepo.all.filter(_.isActive)
    val curPlanEnrollments = {
      val planEnrollmentById = paidAccountRepo.getPlanEnrollments
      plans.map { p => p -> planEnrollmentById.getOrElse(p.id.get, PlanEnrollment.empty) }
    }.toMap
    val oldPlanEnrollments = {
      // mutable map logic ahoy
      val m_planByAccount = mutable.Map(paidAccountRepo.all.map(a => a.id.get -> Option(a.planId)): _*)
      val m_usersByAccount = mutable.Map(paidAccountRepo.all.map(a => a.id.get -> a.activeUsers): _*)

      reverseChronologicalEvents.map(e => (e.accountId, e.action)).collect {
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
      IncomeHistory(old = old.sum, cur = cur.sum)
    }
    AdminPaymentsDashboard(
      totalAmortizedIncomePerMonth = totalIncome,
      creditChanges = creditChanges,
      chargesMade = chargesMade,
      planEnrollment = planEnrollments,
      failedAccounts = failedAccounts,
      frozenAccounts = frozenAccounts
    )
  }
}

case class IncomeHistory(cur: DollarAmount, old: DollarAmount)
case class CreditChangeHistory(cur: DollarAmount, old: DollarAmount)
case class ChargesMadeHistory(cur: DollarAmount, old: DollarAmount)
case class PlanEnrollmentHistory(cur: PlanEnrollment, old: PlanEnrollment)
case class AdminPaymentsDashboard(
  totalAmortizedIncomePerMonth: IncomeHistory,
  creditChanges: CreditChangeHistory,
  chargesMade: ChargesMadeHistory,
  planEnrollment: Map[PaidPlan, PlanEnrollmentHistory],
  frozenAccounts: Seq[AdminAccountView],
  failedAccounts: Seq[AdminAccountView])
