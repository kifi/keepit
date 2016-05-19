package com.keepit.payments

import com.keepit.common.db.slick.{ Repo, DbRepo, DataBaseComponent }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.{ Id, State }
import com.keepit.common.time._
import com.keepit.common.util.DollarAmount
import com.keepit.model.{ Limit, User, Organization }
import com.keepit.common.mail.EmailAddress
import com.keepit.common.core._

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import org.joda.time.DateTime

@ImplementedBy(classOf[PaidAccountRepoImpl])
trait PaidAccountRepo extends Repo[PaidAccount] {
  def getActiveByIds(ids: Set[Id[PaidAccount]])(implicit session: RSession): Map[Id[PaidAccount], PaidAccount]
  //every org should have a PaidAccount, created during org creation. Every time this method gets called that better exist.
  def getByOrgId(orgId: Id[Organization], excludeStates: Set[State[PaidAccount]] = Set(PaidAccountStates.INACTIVE))(implicit session: RSession): PaidAccount
  def getByOrgIds(orgIds: Set[Id[Organization]], excludeStates: Set[State[PaidAccount]] = Set(PaidAccountStates.INACTIVE))(implicit session: RSession): Map[Id[Organization], PaidAccount]
  def maybeGetByOrgId(orgId: Id[Organization], excludeStates: Set[State[PaidAccount]] = Set(PaidAccountStates.INACTIVE))(implicit session: RSession): Option[PaidAccount]
  def getAccountId(orgId: Id[Organization], excludeStates: Set[State[PaidAccount]] = Set(PaidAccountStates.INACTIVE))(implicit session: RSession): Id[PaidAccount]
  def getActiveByPlan(planId: Id[PaidPlan])(implicit session: RSession): Seq[PaidAccount]
  def tryGetAccountLock(orgId: Id[Organization])(implicit session: RWSession): Boolean
  def releaseAccountLock(orgId: Id[Organization])(implicit session: RWSession): Boolean
  def getRenewable()(implicit session: RSession): Seq[PaidAccount]
  def getPayable(maxBalance: DollarAmount)(implicit session: RSession): Seq[PaidAccount]

  // admin/integrity methods
  def getPayingTeams()(implicit session: RSession): Set[Id[Organization]]
  def getIdSubsetByModulus(modulus: Int, partition: Int)(implicit session: RSession): Set[Id[Organization]]
  def getPlanEnrollments(implicit session: RSession): Map[Id[PaidPlan], PlanEnrollment]
  def adminGetPlans(implicit session: RSession): Map[Id[PaidAccount], Id[PaidPlan]]
  def adminGetNumUsers(implicit session: RSession): Map[Id[PaidAccount], Int]

  def deactivate(model: PaidAccount)(implicit session: RWSession): Unit
}

@Singleton
class PaidAccountRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    planRepo: PaidPlanRepoImpl) extends DbRepo[PaidAccount] with PaidAccountRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val statusColumnType = MappedColumnType.base[PaymentStatus, String](_.value, PaymentStatus(_))

  type RepoImpl = PaidAccountTable
  class PaidAccountTable(tag: Tag) extends RepoTable[PaidAccount](db, tag, "paid_account") {
    def orgId = column[Id[Organization]]("org_id", O.NotNull)
    def planId = column[Id[PaidPlan]]("plan_id", O.NotNull)
    def credit = column[DollarAmount]("credit", O.NotNull)
    def planRenewal = column[DateTime]("plan_renewal", O.NotNull)
    def paymentDueAt = column[Option[DateTime]]("payment_due_at", O.Nullable)
    def paymentStatus = column[PaymentStatus]("payment_status", O.NotNull)
    def userContacts = column[Seq[Id[User]]]("user_contacts", O.NotNull)
    def emailContacts = column[Seq[EmailAddress]]("email_contacts", O.NotNull)
    def lockedForProcessing = column[Boolean]("locked_for_processing", O.NotNull)
    def frozen = column[Boolean]("frozen", O.NotNull)
    def activeUsers = column[Int]("active_users", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, orgId, planId, credit, planRenewal, paymentDueAt, paymentStatus, userContacts, emailContacts, lockedForProcessing, frozen, activeUsers) <> ((PaidAccount.apply _).tupled, PaidAccount.unapply _)
  }

  def table(tag: Tag) = new PaidAccountTable(tag)
  initTable()

  override def deleteCache(paidAccount: PaidAccount)(implicit session: RSession): Unit = {}
  override def invalidateCache(paidAccount: PaidAccount)(implicit session: RSession): Unit = {}
  private def activeRows = rows.filter(row => row.state === PaidAccountStates.ACTIVE)

  def getActiveByIds(ids: Set[Id[PaidAccount]])(implicit session: RSession): Map[Id[PaidAccount], PaidAccount] = {
    rows.filter(row => row.id.inSet(ids) && row.state === PaidAccountStates.ACTIVE).list.map { acc => acc.id.get -> acc }.toMap
  }

  def getByOrgId(orgId: Id[Organization], excludeStates: Set[State[PaidAccount]] = Set(PaidAccountStates.INACTIVE))(implicit session: RSession): PaidAccount = {
    (for (row <- rows if row.orgId === orgId && !row.state.inSet(excludeStates)) yield row).first
  }
  def getByOrgIds(orgIds: Set[Id[Organization]], excludeStates: Set[State[PaidAccount]] = Set(PaidAccountStates.INACTIVE))(implicit session: RSession): Map[Id[Organization], PaidAccount] = {
    rows.filter(row => row.orgId.inSet(orgIds) && !row.state.inSet(excludeStates)).list.map(r => r.orgId -> r).toMap
  }

  def maybeGetByOrgId(orgId: Id[Organization], excludeStates: Set[State[PaidAccount]] = Set(PaidAccountStates.INACTIVE))(implicit session: RSession): Option[PaidAccount] = {
    (for (row <- rows if row.orgId === orgId && !row.state.inSet(excludeStates)) yield row).firstOption
  }

  def getAccountId(orgId: Id[Organization], excludeStates: Set[State[PaidAccount]] = Set(PaidAccountStates.INACTIVE))(implicit session: RSession): Id[PaidAccount] = {
    (for (row <- rows if row.orgId === orgId && !row.state.inSet(excludeStates)) yield row.id).first
  }

  def getActiveByPlan(planId: Id[PaidPlan])(implicit session: RSession): Seq[PaidAccount] = {
    (for (row <- rows if row.planId === planId && row.state === PaidAccountStates.ACTIVE) yield row).list
  }

  def tryGetAccountLock(orgId: Id[Organization])(implicit session: RWSession): Boolean = {
    (for (row <- rows if row.orgId === orgId && row.lockedForProcessing =!= true) yield row.lockedForProcessing).update(true) > 0
  }

  def releaseAccountLock(orgId: Id[Organization])(implicit session: RWSession): Boolean = {
    (for (row <- rows if row.orgId === orgId && row.lockedForProcessing === true) yield row.lockedForProcessing).update(false) > 0
  }

  def getRenewable()(implicit session: RSession): Seq[PaidAccount] = {
    (for (row <- rows if row.state === PaidAccountStates.ACTIVE && !row.frozen && row.planRenewal <= clock.now()) yield row).sortBy(_.planRenewal).list
  }

  def getPayable(maxBalance: DollarAmount)(implicit session: RSession): Seq[PaidAccount] = {
    (for (row <- rows if row.state === PaidAccountStates.ACTIVE && !row.frozen && row.paymentStatus === (PaymentStatus.Ok: PaymentStatus) && (row.credit < -maxBalance || row.paymentDueAt <= clock.now())) yield row).sortBy(_.paymentDueAt).list
  }
  def getIdSubsetByModulus(modulus: Int, partition: Int)(implicit session: RSession): Set[Id[Organization]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"""select org_id from paid_account where state='active' and frozen = false and MOD(id, $modulus)=$partition""".as[Id[Organization]].list.toSet
  }
  def getPayingTeams()(implicit session: RSession): Set[Id[Organization]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"""select * from paid_account where state = 'active' and plan_id in (select id from paid_plan where price_per_user_per_cycle > 0)""".as[Id[Organization]].list.toSet
  }
  def getPlanEnrollments(implicit session: RSession): Map[Id[PaidPlan], PlanEnrollment] = {
    activeRows.groupBy(_.planId).map { case (planId, accounts) => planId -> (accounts.length, accounts.map(_.activeUsers).sum) }.list.map {
      case (planId, (numAccounts, numUsers)) => planId -> PlanEnrollment(numAccounts = numAccounts, numActiveUsers = numUsers.getOrElse(0))
    }.toMap
  }
  def adminGetPlans(implicit session: RSession): Map[Id[PaidAccount], Id[PaidPlan]] = {
    activeRows.map(r => r.id -> r.planId).list.toMap
  }
  def adminGetNumUsers(implicit session: RSession): Map[Id[PaidAccount], Int] = {
    activeRows.map(r => r.id -> r.activeUsers).list.toMap
  }

  def deactivate(model: PaidAccount)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }
}
