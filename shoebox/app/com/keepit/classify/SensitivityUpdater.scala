package com.keepit.classify

import org.joda.time.DateTime

import com.google.inject.{Inject, ImplementedBy}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession

@ImplementedBy(classOf[SensitivityUpdaterImpl])
trait SensitivityUpdater {
  def updateSensitivity(domain: Domain)(implicit session: RWSession): Option[Boolean]
  def updateDomainsChangedSince(dateTime: DateTime)(implicit session: RWSession)
}

// TODO(greg): do this stuff with fewer SQL queries (should be able to do it all in SQL)
class SensitivityUpdaterImpl @Inject()
    (domainRepo: DomainRepo, tagRepo: DomainTagRepo, domainToTagRepo: DomainToTagRepo) extends SensitivityUpdater {

  def updateSensitivity(domain: Domain)(implicit session: RWSession): Option[Boolean] = {
    val tags = tagRepo.getTags(domainToTagRepo.getByDomain(domain.id.get).map(_.tagId))
    val sensitive = tags.map(_.sensitive).foldLeft(Some(false): Option[Boolean]) {
      // if any tags are sensitive, assume the domain is sensitive
      case (Some(true), _) | (_, Some(true)) => Some(true)
      // else if any are unknown, assume unknown (could be sensitive)
      case (None, _) | (_, None) => None
      // otherwise if all are not sensitive, assume not sensitive
      case _ => Some(false)
    }
    if (domain.autoSensitive != sensitive) {
      domainRepo.save(domain.withAutoSensitive(sensitive)).sensitive
    } else {
      domain.sensitive
    }
  }

  // update the relationships changed since the given time
  def updateDomainsChangedSince(dateTime: DateTime)(implicit session: RWSession) {
    domainToTagRepo.getDomainsChangedSince(dateTime).foreach { domainId: Id[Domain] =>
      updateSensitivity(domainRepo.get(domainId))
    }
  }
}
