package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import java.sql.Connection
import org.joda.time.DateTime
import play.api.libs.json.{JsArray, JsValue}

case class SliderRule (
  id: Option[Id[SliderRule]] = None,
  groupName: String,
  name: String,
  parameters: Option[JsArray] = None,
  state: State[SliderRule] = SliderRuleStates.ACTIVE,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime
) extends Model[SliderRule] {
  def withId(id: Id[SliderRule]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[SliderRule]) = this.copy(state = state)
  def withParameters(parameters: Option[JsArray]) = this.copy(parameters = parameters)
  def isActive = this.state == SliderRuleStates.ACTIVE
}

object SliderRuleStates extends States[SliderRule]

@ImplementedBy(classOf[SliderRuleRepoImpl])
trait SliderRuleRepo extends Repo[SliderRule] {
  def getGroup(groupName: String)(implicit session: RSession): Seq[SliderRule]
}

@Singleton
class SliderRuleRepoImpl @Inject() (val db: DataBaseComponent) extends DbRepo[SliderRule] with SliderRuleRepo {
  import FortyTwoTypeMappers._
  import org.scalaquery.ql._
  import org.scalaquery.ql.ColumnOps._
  import org.scalaquery.ql.basic.BasicProfile
  import org.scalaquery.ql.extended.ExtendedTable
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[SliderRule](db, "slider_rule") {
    def groupName = column[String]("group_name", O.NotNull)
    def name = column[String]("name", O.NotNull)
    def parameters = column[JsArray]("parameters", O.Nullable)
    def * = id.? ~ groupName ~ name ~ parameters.? ~ state ~ createdAt ~ updatedAt <> (SliderRule, SliderRule.unapply _)
  }

  def getGroup(groupName: String)(implicit session: RSession): Seq[SliderRule] =
    (for(f <- table if f.groupName === groupName) yield f).list
}
