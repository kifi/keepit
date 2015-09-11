package com.keepit.payments

import com.keepit.common.db.slick.{ Repo, DbRepo, DataBaseComponent }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.{ Id, State }
import com.keepit.common.time.Clock
import com.keepit.model.{ Name, User, Organization }
import com.keepit.common.mail.EmailAddress

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import play.api.libs.json.{ Json, JsString, JsObject }

@ImplementedBy(classOf[PaidAccountRepoImpl])
trait PaidAccountRepo extends Repo[PaidAccount] {
  //every org should have a PaidAccount, created during org creation. Every time this method gets called that better exist.
  def getByOrgId(orgId: Id[Organization], excludeStates: Set[State[PaidAccount]] = Set(PaidAccountStates.INACTIVE))(implicit session: RSession): PaidAccount
  def maybeGetByOrgId(orgId: Id[Organization], excludeStates: Set[State[PaidAccount]] = Set(PaidAccountStates.INACTIVE))(implicit session: RSession): Option[PaidAccount]
  def getAccountId(orgId: Id[Organization], excludeStates: Set[State[PaidAccount]] = Set(PaidAccountStates.INACTIVE))(implicit session: RSession): Id[PaidAccount]
  def getActiveByPlan(planId: Id[PaidPlan])(implicit session: RSession): Seq[PaidAccount]
  def tryGetAccountLock(orgId: Id[Organization])(implicit session: RWSession): Boolean
  def releaseAccountLock(orgId: Id[Organization])(implicit session: RWSession): Boolean
}

@Singleton
class PaidAccountRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[PaidAccount] with PaidAccountRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val dollarAmountColumnType = MappedColumnType.base[DollarAmount, Int](_.cents, DollarAmount(_))
  implicit val settingsConfigColumnType = MappedColumnType.base[Map[Name[PlanFeature], Setting], String](
    { obj => Json.stringify(Json.toJson(obj.map { case (name, setting) => name.toString -> setting.value })) },
    { str => Json.parse(str).as[Map[String, String]].map { case (name, setting) => Name[PlanFeature](name) -> Setting(setting) } }
  )

  type RepoImpl = PaidAccountTable
  class PaidAccountTable(tag: Tag) extends RepoTable[PaidAccount](db, tag, "paid_account") {
    def orgId = column[Id[Organization]]("org_id", O.NotNull)
    def planId = column[Id[PaidPlan]]("plan_id", O.NotNull)
    def credit = column[DollarAmount]("credit", O.NotNull)
    def userContacts = column[Seq[Id[User]]]("user_contacts", O.NotNull)
    def emailContacts = column[Seq[EmailAddress]]("email_contacts", O.NotNull)
    def lockedForProcessing = column[Boolean]("locked_for_processing", O.NotNull)
    def frozen = column[Boolean]("frozen", O.NotNull)
    def settingsByFeature = column[Map[Name[PlanFeature], Setting]]("settings_by_feature", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, orgId, planId, credit, userContacts, emailContacts, lockedForProcessing, frozen, settingsByFeature) <> ((PaidAccount.apply _).tupled, PaidAccount.unapply _)
  }

  def table(tag: Tag) = new PaidAccountTable(tag)
  initTable()

  override def deleteCache(paidAccount: PaidAccount)(implicit session: RSession): Unit = {}

  override def invalidateCache(paidAccount: PaidAccount)(implicit session: RSession): Unit = {}

  def getByOrgId(orgId: Id[Organization], excludeStates: Set[State[PaidAccount]] = Set(PaidAccountStates.INACTIVE))(implicit session: RSession): PaidAccount = {
    (for (row <- rows if row.orgId === orgId && !row.state.inSet(excludeStates)) yield row).first
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

}
