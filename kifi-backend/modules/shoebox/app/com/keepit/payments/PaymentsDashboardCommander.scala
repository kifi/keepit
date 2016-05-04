package com.keepit.payments

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import com.keepit.common.util.{ Paginator, DollarAmount }
import com.keepit.controllers.admin.AdminAccountView
import com.keepit.model.{ OrganizationExperimentType, OrganizationExperimentRepo, OrganizationRepo }
import org.joda.time.{ DateTime, Period }

import scala.collection.mutable

@ImplementedBy(classOf[PaymentsDashboardCommanderImpl])
trait PaymentsDashboardCommander {
  def generateDashboard(): AdminPaymentsDashboard
}

@Singleton
class PaymentsDashboardCommanderImpl @Inject() (
    db: Database,
    orgRepo: OrganizationRepo,
    orgExpRepo: OrganizationExperimentRepo,
    paidPlanRepo: PaidPlanRepo,
    paidAccountRepo: PaidAccountRepo,
    accountEventRepo: AccountEventRepo,
    creditRewardRepo: CreditRewardRepo,
    clock: Clock) extends PaymentsDashboardCommander {

  private def createAdminAccountView(account: PaidAccount)(implicit session: RSession): AdminAccountView = {
    AdminAccountView(
      organization = orgRepo.get(account.orgId)
    )
  }

  private def creditChangesFn(events: Seq[AccountEvent])(implicit session: RSession): DollarAmount = {
    events.collect { case e if e.creditChange < DollarAmount.ZERO => e.creditChange }.sum
  }
  private def chargesMadeFn(events: Seq[AccountEvent])(implicit session: RSession): DollarAmount = {
    events.flatMap(_.paymentCharge).sum
  }
  private def rewardsGrantedFn(events: Seq[AccountEvent])(implicit session: RSession): Map[String, DollarAmount] = {
    val rewardKindsAndValues = events.flatMap { e =>
      e.action match {
        case AccountEventAction.RewardCredit(id) => Some(creditRewardRepo.get(id).reward.kind.kind -> e.creditChange)
        case _ => None
      }
    }
    rewardKindsAndValues.groupBy(_._1).map { case (kind, kindAndValues) => kind -> kindAndValues.map(_._2).sum }.withDefaultValue(DollarAmount.ZERO)
  }

  def generateDashboard(): AdminPaymentsDashboard = db.readOnlyMaster { implicit session =>
    val totalMoneyEarned = accountEventRepo.adminTotalMoneyEarned
    val fakeAccountIds = {
      val fakeOrgIds = orgExpRepo.getOrganizationsByExperiment(OrganizationExperimentType.FAKE).toSet
      paidAccountRepo.getByOrgIds(fakeOrgIds).values.map(_.id.get).toSet
    }

    val now = clock.now
    val diffPeriod = Period.weeks(1)
    val plans = paidPlanRepo.aTonOfRecords.filter(_.isActive)
    val Seq(reverseChronologicalEvents, reallyOldEvents) = Seq(now, now.minus(diffPeriod)).map { end =>
      accountEventRepo.adminGetByKindAndDate(AccountEventKind.all, start = end.minus(diffPeriod), end = end)
        .filter(e => !fakeAccountIds.contains(e.accountId))
        .toList.sortBy(e => (e.eventTime.getMillis, e.id.get.id)).reverse
    }

    val frozenAccounts = paidAccountRepo.aTonOfRecords.filter(a => a.isActive && a.frozen).take(100).map(createAdminAccountView) // God help us if we have more than 100 frozen accounts
    val failedAccounts = paidAccountRepo.aTonOfRecords.filter(a => a.isActive && a.paymentStatus == PaymentStatus.Failed).take(100).map(createAdminAccountView) // God help us if we have more than 100 failed accounts

    val upgradedPlans = plans.filter(_.pricePerCyclePerUser > DollarAmount.ZERO).map(_.id.get).toSet
    val upgradedAccounts = paidAccountRepo.aTonOfRecords.filter(a => a.isActive && upgradedPlans.contains(a.planId)).sortBy(_.planRenewal.getMillis).reverse.map(createAdminAccountView)

    val (creditChanges, chargesMade, rewardsGranted) = {
      def applyFn[T](fn: Seq[AccountEvent] => T): History[T] = History(old = fn(reallyOldEvents), cur = fn(reverseChronologicalEvents))
      (applyFn(creditChangesFn), applyFn(chargesMadeFn), applyFn(rewardsGrantedFn))
    }

    val billingHistory = (1 to 7).toSet.map(now.minusDays).map { start =>
      val events = accountEventRepo.adminGetByKindAndDate(AccountEventKind.all, start = start, end = start.plusDays(1))
        .filter(e => !fakeAccountIds.contains(e.accountId))
        .toList.sortBy(e => (e.eventTime.getMillis, e.id.get.id)).reverse
      start -> (creditChangesFn(events), rewardsGrantedFn(events).values.sum, chargesMadeFn(events))
    }.toMap

    val curPlanEnrollments = {
      val planEnrollmentById = paidAccountRepo.getPlanEnrollments
      plans.map { p => p -> planEnrollmentById.getOrElse(p.id.get, PlanEnrollment.empty) }
    }.toMap
    val oldPlanEnrollments = {
      // mutable map logic ahoy
      val m_planByAccount = mutable.Map(paidAccountRepo.aTonOfRecords.filter(_.isActive).map(a => a.id.get -> Option(a.planId)): _*)
      val m_usersByAccount = mutable.Map(paidAccountRepo.aTonOfRecords.filter(_.isActive).map(a => a.id.get -> a.activeUsers): _*).withDefaultValue(0)

      reverseChronologicalEvents.map(e => (e.accountId, e.action)).foreach {
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
    val planEnrollments = History(cur = curPlanEnrollments, old = oldPlanEnrollments)
    val totalDailyIncome = {
      val (old, cur) = plans.map { plan =>
        val amortizedIncomePerUser = DollarAmount.cents(plan.pricePerCyclePerUser.toCents / plan.billingCycle.months / now.dayOfMonth().withMaximumValue().getDayOfMonth)
        (amortizedIncomePerUser * planEnrollments.old(plan).numActiveUsers, amortizedIncomePerUser * planEnrollments.cur(plan).numActiveUsers)
      }.unzip
      History(old = old.sum, cur = cur.sum)
    }
    AdminPaymentsDashboard(
      totalMoneyEarned = totalMoneyEarned,
      plans = plans,
      diffPeriod = diffPeriod,
      totalAmortizedDailyIncome = totalDailyIncome,
      creditChanges = creditChanges,
      chargesMade = chargesMade,
      rewardsGranted = rewardsGranted,
      billingHistory = billingHistory,
      planEnrollment = planEnrollments,
      failedAccounts = failedAccounts,
      frozenAccounts = frozenAccounts,
      upgradedAccounts = upgradedAccounts
    )
  }
}

case class History[T](cur: T, old: T) {
  def map[B](f: (T => B)) = History(cur = f(cur), old = f(old))
}
case class AdminPaymentsDashboard(
  totalMoneyEarned: DollarAmount,
  plans: Seq[PaidPlan],
  diffPeriod: Period,
  totalAmortizedDailyIncome: History[DollarAmount],
  creditChanges: History[DollarAmount],
  chargesMade: History[DollarAmount],
  billingHistory: Map[DateTime, (DollarAmount, DollarAmount, DollarAmount)],
  rewardsGranted: History[Map[String, DollarAmount]], // this is a string because RewardKind is being obnoxious
  planEnrollment: History[Map[PaidPlan, PlanEnrollment]],
  frozenAccounts: Seq[AdminAccountView],
  failedAccounts: Seq[AdminAccountView],
  upgradedAccounts: Seq[AdminAccountView])
