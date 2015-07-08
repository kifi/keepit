package com.keepit.rover.rule

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.Database
import com.keepit.rover.model.{ RoverHttpProxy, HttpProxy, RoverHttpProxyRepo }

import scala.concurrent.Future

@Singleton
class RoverHttpProxyCommander @Inject() (
    val db: Database,
    val httpProxyRepo: RoverHttpProxyRepo) {

  def all: Future[Seq[HttpProxy]] = db.readOnlyMaster { implicit session =>
    Future.successful(httpProxyRepo.all.map(roverHttpProxyToHttpProxy))
  }

  def save(that: HttpProxy): Future[HttpProxy] = {
    db.readWrite { implicit session =>
      val exists = that.id.isDefined
      val updatedData = RoverHttpProxy(
        state = State[RoverHttpProxy](that.state.value),
        alias = that.alias,
        host = that.host,
        port = that.port,
        scheme = that.scheme,
        username = that.username,
        password = that.password
      )
      val newProxy =
        if (exists) {
          val current = httpProxyRepo.get(Id(that.id.get.id))
          updatedData.copy(
            id = current.id,
            createdAt = current.createdAt)
        } else updatedData
      Future.successful(roverHttpProxyToHttpProxy(httpProxyRepo.save(newProxy)))
    }
  }

  def roverHttpProxyToHttpProxy(httpProxy: RoverHttpProxy): HttpProxy = {
    HttpProxy(
      id = httpProxy.id.map(id => Id(id.id)),
      state = State(httpProxy.state.value),
      alias = httpProxy.alias,
      host = httpProxy.host,
      port = httpProxy.port,
      scheme = httpProxy.scheme,
      username = httpProxy.username,
      password = httpProxy.password
    )
  }

}
