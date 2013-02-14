package com.keepit.classify

import com.google.inject.{Inject, ImplementedBy}
import com.keepit.common.db.slick.DBSession.RWSession

@ImplementedBy(classOf[SensitivityUpdaterImpl])
trait SensitivityUpdater {
  def updateSensitivity(domains: Seq[Domain])(implicit session: RWSession): Seq[Domain]
}

class SensitivityUpdaterImpl @Inject()
    (domainRepo: DomainRepo, tagRepo: DomainTagRepo, domainToTagRepo: DomainToTagRepo) extends SensitivityUpdater {
  def updateSensitivity(domains: Seq[Domain])(implicit session: RWSession): Seq[Domain] = {
    domains.map { domain =>
      val tags = tagRepo.getTags(domainToTagRepo.getByDomain(domain.id.get).map(_.tagId))
      val sensitive = tags.map(_.sensitive).foldLeft(Some(false): Option[Boolean]) {
        // if any tags are sensitive, assume the domain is sensitive
        case (Some(true), _) | (_, Some(true)) => Some(true)
        // else if any are unknown, assume unknown (could be sensitive)
        case (None, _) | (_, None) => None
        // otherwise if all are not sensitive, assume not sensitive
        case _ => Some(false)
      }
      domainRepo.save(domain.withAutoSensitive(sensitive))
    }
  }
}
