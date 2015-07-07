package com.keepit.rover.rule

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.Database
import com.keepit.rover.model.{ RoverHttpProxy, HttpProxy, RoverHttpProxyRepo }

@Singleton
class RoverHttpProxyCommander @Inject() (
    val db: Database,
    val httpProxyRepo: RoverHttpProxyRepo) {

  def all = db.readOnlyMaster { implicit session =>
    httpProxyRepo.all
  }

  implicit def roverToView(httpProxy: RoverHttpProxy): HttpProxy = {
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
