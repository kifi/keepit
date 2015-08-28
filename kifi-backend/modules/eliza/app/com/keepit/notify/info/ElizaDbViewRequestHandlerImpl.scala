package com.keepit.notify.info

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class ElizaDbViewRequestHandlerImpl @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient,
    implicit val ec: ExecutionContext) extends DbViewRequestHandler {

  val handlers = DbViewRequestHandlers()
    .add(DbViewKey.user) { requests =>
      shoeboxServiceClient.getUsers(requests.map(_.id)).map { users =>
        users.map(user => (user.id.get, user)).toMap
      }
    }.add(DbViewKey.library) { requests =>
      shoeboxServiceClient.getLibraries(requests.map(_.id))
    }.add(DbViewKey.keep) { requests =>
      shoeboxServiceClient.getKeeps(requests.map(_.id))
    }.add(DbViewKey.organization) { requests =>
      shoeboxServiceClient.getOrganizations(requests.map(_.id))
    }

}
