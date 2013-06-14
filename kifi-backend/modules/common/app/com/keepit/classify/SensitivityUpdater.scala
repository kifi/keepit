package com.keepit.classify

import org.joda.time.DateTime

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession

@ImplementedBy(classOf[SensitivityUpdaterImpl])
trait SensitivityUpdater {
  def calculateSensitivity(domain: Domain)(implicit session: RWSession): Option[Boolean]
  def clearDomainsChangedSince(dateTime: DateTime)(implicit session: RWSession)
  def clearDomainSensitivity(domains: Seq[Id[Domain]])(implicit session: RWSession)
}

@Singleton
class SensitivityUpdaterImpl @Inject()
    (domainRepo: DomainRepo, tagRepo: DomainTagRepo, domainToTagRepo: DomainToTagRepo) extends SensitivityUpdater {

  def calculateSensitivity(domain: Domain)(implicit session: RWSession): Option[Boolean] = {
    val tags = tagRepo.getTags(domainToTagRepo.getByDomain(domain.id.get).map(_.tagId))
    val sensitive = tags.map(_.sensitive).foldLeft(None: Option[Boolean]) { (a, b) =>
      Some(a.getOrElse(false) || b.getOrElse(false))
    }
    for (s <- sensitive if domain.autoSensitive != sensitive) {
      domainRepo.updateAutoSensitivity(Seq(domain.id.get), Some(s))
    }
    sensitive
  }

  // clear the domains since a given time
  def clearDomainsChangedSince(dateTime: DateTime)(implicit session: RWSession) {
    clearDomainSensitivity(domainToTagRepo.getDomainsChangedSince(dateTime).toSeq)
  }
  def clearDomainSensitivity(domainIds: Seq[Id[Domain]])(implicit session: RWSession) {
    domainIds.grouped(1000).foreach { domainIds =>
      domainRepo.updateAutoSensitivity(domainIds.toSeq, None)
    }
  }
}
