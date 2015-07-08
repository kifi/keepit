package com.keepit.rover.rule

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.{ Id, State }
import com.keepit.common.db.slick.Database
import com.keepit.rover.model.{ RoverUrlRule, UrlRule, RoverUrlRuleRepo }

import scala.concurrent.Future

@Singleton
class RoverUrlRuleCommander @Inject() (
    val db: Database,
    val urlRuleRepo: RoverUrlRuleRepo) {

  def all: Future[Seq[UrlRule]] = db.readOnlyMaster { implicit session =>
    Future.successful(urlRuleRepo.all.map(roverUrlRuleToUrlRule))
  }

  def save(that: UrlRule): Future[UrlRule] = {
    db.readWrite { implicit session =>
      val exists = that.id.isDefined
      val updatedData = RoverUrlRule(
        state = State[RoverUrlRule](that.state.value),
        pattern = that.pattern,
        proxy = that.proxy.map(id => Id(id.id))
      )
      val newProxy =
        if (exists) {
          val current = urlRuleRepo.get(Id(that.id.get.id))
          updatedData.copy(
            id = current.id,
            createdAt = current.createdAt)
        } else updatedData
      Future.successful(roverUrlRuleToUrlRule(urlRuleRepo.save(newProxy)))
    }
  }

  def roverUrlRuleToUrlRule(urlRule: RoverUrlRule): UrlRule = {
    UrlRule(
      id = urlRule.id.map(id => Id(id.id)),
      state = State(urlRule.state.value),
      pattern = urlRule.pattern,
      proxy = urlRule.proxy.map(id => Id(id.id))
    )
  }

}
