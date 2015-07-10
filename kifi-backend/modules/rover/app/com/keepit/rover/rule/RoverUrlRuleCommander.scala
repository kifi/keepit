package com.keepit.rover.rule

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.{ Id, State }
import com.keepit.common.db.slick.Database
import com.keepit.rover.model._

import scala.concurrent.Future

@Singleton
class RoverUrlRuleCommander @Inject() (
    val db: Database,
    val urlRuleRepo: RoverUrlRuleRepo,
    val httpProxyRepo: RoverHttpProxyRepo,
    val httpProxyCommander: RoverHttpProxyCommander) {

  def all: Future[Seq[UrlRule]] = db.readOnlyMaster { implicit session =>
    Future.successful(urlRuleRepo.all.map(roverUrlRuleToUrlRule))
  }

  def save(that: UrlRule): Future[UrlRule] = {
    db.readWrite { implicit session =>
      val exists = that.id.isDefined
      val updatedData = RoverUrlRule(
        state = State[RoverUrlRule](that.state.value),
        pattern = that.pattern,
        example = that.example,
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

  def actionsFor(url: String): Seq[UrlRuleAction] = {
    db.readOnlyMaster { implicit session =>
      urlRuleRepo.actionsFor(url)
    }
  }

  def proxyFor(url: String): Option[RoverHttpProxy] = {
    db.readOnlyMaster { implicit session =>
      actionsFor(url).collect {
        case UrlRuleAction.UseProxy(proxyId) => proxyId
      }.headOption.map { proxyId =>
        httpProxyRepo.get(proxyId)
      }
    }
  }

  def lightweightProxyFor(url: String): Option[HttpProxy] =
    proxyFor(url).map(httpProxyCommander.roverHttpProxyToHttpProxy)

  def roverUrlRuleToUrlRule(urlRule: RoverUrlRule): UrlRule = {
    UrlRule(
      id = urlRule.id.map(id => Id(id.id)),
      state = State(urlRule.state.value),
      pattern = urlRule.pattern,
      example = urlRule.example,
      proxy = urlRule.proxy.map(id => Id(id.id))
    )
  }

}
