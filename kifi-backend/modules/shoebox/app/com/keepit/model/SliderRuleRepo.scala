package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.State
import com.keepit.common.time.Clock
import scala.Some
import play.api.libs.json.JsArray
import scala.collection.mutable

@ImplementedBy(classOf[SliderRuleRepoImpl])
trait SliderRuleRepo extends Repo[SliderRule] {
  def getGroup(groupName: String)(implicit session: RSession): SliderRuleGroup
  def getByName(name: String, excludeState: Option[State[SliderRule]] = Some(SliderRuleStates.INACTIVE))
               (implicit session: RSession): Seq[SliderRule]
}

@Singleton
class SliderRuleRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[SliderRule] with SliderRuleRepo {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[SliderRule](db, "slider_rule") {
    def groupName = column[String]("group_name", O.NotNull)
    def name = column[String]("name", O.NotNull)
    def parameters = column[JsArray]("parameters", O.Nullable)
    def * = id.? ~ groupName ~ name ~ parameters.? ~ state ~ createdAt ~ updatedAt <> (SliderRule, SliderRule.unapply _)
  }

  val groupCache: mutable.Map[String, SliderRuleGroup] =   // name -> group TODO: memcache?
    new mutable.HashMap[String, SliderRuleGroup] with mutable.SynchronizedMap[String, SliderRuleGroup] {}

  override def save(rule: SliderRule)(implicit session: RWSession): SliderRule = {
    val newRule = super.save(rule)
    groupCache -= rule.groupName
    newRule
  }

  def getGroup(groupName: String)(implicit session: RSession): SliderRuleGroup =
    groupCache.getOrElseUpdate(groupName, SliderRuleGroup((for(r <- table if r.groupName === groupName) yield r).list))

  def getByName(name: String, excludeState: Option[State[SliderRule]] = Some(SliderRuleStates.INACTIVE))
               (implicit session: RSession): Seq[SliderRule] =
    (for(r <- table if r.name === name && r.state =!= excludeState.getOrElse(null)) yield r).list
}

