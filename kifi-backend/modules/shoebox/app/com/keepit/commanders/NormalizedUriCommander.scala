package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.model.{ Restriction, NormalizedURIRepo, NormalizedURI }

import scala.concurrent.Future

class NormalizedURICommander @Inject() (
    normalizedURIRepo: NormalizedURIRepo,
    db: Database) extends Logging {

  def getAdultRestrictionOfURIs(uris: Seq[Id[NormalizedURI]]): Future[Seq[Boolean]] = {
    db.readOnlyReplicaAsync { implicit session =>
      uris.map { uri =>
        normalizedURIRepo.getRestrictionStatusOfURIs(Restriction.ADULT, uri)
      }
    }
  }

}
