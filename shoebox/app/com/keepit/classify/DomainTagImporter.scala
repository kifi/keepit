package com.keepit.classify

import com.google.inject.{ImplementedBy, Inject}
import com.keepit.common.db.slick.DBSession.{RSession, RWSession}


@ImplementedBy(classOf[DomainTagImporterImpl])
trait DomainTagImporter {
  def removeTag(tagName: DomainTagName)(implicit session: RWSession): Option[DomainTag]
  def applyTagToDomains(tagName: DomainTagName, domainNames: Iterable[String])(implicit session: RWSession)
}

class DomainTagImporterImpl @Inject()
    (domainRepo: DomainRepo, tagRepo: DomainTagRepo, domainToTagRepo: DomainToTagRepo)
    extends DomainTagImporter {

  def removeTag(tagName: DomainTagName)(implicit session: RWSession): Option[DomainTag] = {
    tagRepo.get(tagName, excludeState = None).map { tag =>
      applyTagToDomains(tagName, Seq())
      tagRepo.save(tag.withState(DomainTagStates.INACTIVE))
    }
  }

  def applyTagToDomains(tagName: DomainTagName, domainNames: Iterable[String])(implicit session: RWSession) {
    val tag = tagRepo.get(tagName, excludeState = None) match {
      case Some(tag) if tag.state != DomainTagStates.ACTIVE => tagRepo.save(tag.withState(DomainTagStates.ACTIVE))
      case Some(tag) => tag
      case None => tagRepo.save(DomainTag(name = tagName))
    }
    val tagId = tag.id.get
    val existingDomainToTags = domainToTagRepo.getByTag(tagId, excludeState = None)

    // get domains, saving ones that don't exist exist
    val domainsAppliedTo = domainNames.toSet.map { hostname: String =>
      domainRepo.get(hostname) match {
        case Some(domain) if domain.state != DomainTagStates.ACTIVE =>
          domainRepo.save(domain.withState(DomainStates.ACTIVE))
        case Some(domain) => domain
        case None => domainRepo.save(Domain(hostname = hostname))
      }
    }
    val domainIdsAppliedTo = domainsAppliedTo.map(_.id.get)

    // figure out what changes we need to make; construct a list of tuples containing the old and new value
    val oldNewTuples = existingDomainToTags.map { dtt =>
      val shouldBeActive = domainIdsAppliedTo.contains(dtt.domainId)
      (dtt, dtt.state match {
        case DomainToTagStates.ACTIVE if !shouldBeActive =>
          domainToTagRepo.save(dtt.withState(DomainToTagStates.INACTIVE))
        case DomainToTagStates.INACTIVE if shouldBeActive =>
          domainToTagRepo.save(dtt.withState(DomainToTagStates.ACTIVE))
        case _ => dtt
      })
    }

    val activeDomainIds = oldNewTuples.collect { case (a, b) if b.state == DomainTagStates.ACTIVE => b.domainId }
    val removedDomains = oldNewTuples.collect {
      case (a, b) if a.state == DomainTagStates.ACTIVE && b.state == DomainTagStates.INACTIVE =>
        domainRepo.get(b.domainId)
    }

    // insert new domain tags with the domain ids and the current tag id
    domainToTagRepo.insertAll((domainIdsAppliedTo -- activeDomainIds).toSeq.map { domainId =>
      DomainToTag(tagId = tagId, domainId = domainId)
    })

    // update the sensitivity for all changed domains
    (domainsAppliedTo ++ removedDomains).foreach { domain =>
      domainRepo.save(domain.withAutoSensitive(calculateSensitivity(domain)))
    }
  }

  private def calculateSensitivity(domain: Domain)(implicit session: RSession): Option[Boolean] = {
    val tags = tagRepo.getTags(domainToTagRepo.getByDomain(domain.id.get).map(_.tagId))
    tags.map(_.sensitive).foldLeft(Some(false): Option[Boolean]) {
      // if any tags are sensitive, assume the domain is sensitive
      case (Some(true), _) | (_, Some(true)) => Some(true)
      // else if any are unknown, assume unknown (could be sensitive)
      case (None, _) | (_, None) => None
      // otherwise if all are not sensitive, assume not sensitive
      case _ => Some(false)
    }
  }
}
