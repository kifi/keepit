package com.keepit.payments

import com.keepit.common.db.slick.{ InvalidDatabaseEncodingException, Repo, DbRepo, DataBaseComponent }
import com.keepit.common.db.{ State }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.json.TraversableFormat
import com.keepit.common.time.Clock
import com.keepit.common.util.DollarAmount
import com.keepit.model._

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import play.api.libs.json.{ JsArray, Json }

@ImplementedBy(classOf[PaidPlanRepoImpl])
trait PaidPlanRepo extends Repo[PaidPlan] {
  def getByKinds(kinds: Set[PaidPlan.Kind])(implicit session: RSession): Seq[PaidPlan]
  def getByDisplayName(displayName: String)(implicit session: RSession): Set[PaidPlan]
}

@Singleton
class PaidPlanRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[PaidPlan] with PaidPlanRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val billingCycleColumnType = MappedColumnType.base[BillingCycle, Int](_.months, BillingCycle(_))
  implicit val kindColumnType = MappedColumnType.base[PaidPlan.Kind, String](_.name, PaidPlan.Kind(_))
  implicit val featureSetTypeMapper = MappedColumnType.base[Set[Feature], String](
    { obj => Json.stringify(Json.toJson(obj)) },
    { str => Json.parse(str).as[Set[Feature]] }
  )

  type RepoImpl = PaidPlanTable
  class PaidPlanTable(tag: Tag) extends RepoTable[PaidPlan](db, tag, "paid_plan") {
    def kind = column[PaidPlan.Kind]("kind", O.NotNull)
    def name = column[Name[PaidPlan]]("name", O.NotNull)
    def displayName = column[String]("display_name", O.NotNull)
    def billingCycle = column[BillingCycle]("billing_cycle", O.NotNull)
    def pricePerCyclePerUser = column[DollarAmount]("price_per_user_per_cycle", O.NotNull)
    def editableFeatures = column[Set[Feature]]("editable_features", O.NotNull)
    def defaultSettings = column[OrganizationSettings]("default_settings", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, kind, name, displayName, billingCycle, pricePerCyclePerUser, editableFeatures, defaultSettings) <> ((PaidPlan.apply _).tupled, PaidPlan.unapply _)
  }

  def table(tag: Tag) = new PaidPlanTable(tag)
  initTable()

  override def deleteCache(paidPlan: PaidPlan)(implicit session: RSession): Unit = {}

  override def invalidateCache(paidPlan: PaidPlan)(implicit session: RSession): Unit = {}

  def getByKinds(states: Set[PaidPlan.Kind])(implicit session: RSession): Seq[PaidPlan] = {
    (for (row <- rows if row.state === PaidPlanStates.ACTIVE && row.kind.inSet(states)) yield row).list
  }

  def getByDisplayName(displayName: String)(implicit session: RSession): Set[PaidPlan] = {
    rows.filter(row => row.state === PaidPlanStates.ACTIVE && row.displayName === displayName).list.toSet
  }

}
