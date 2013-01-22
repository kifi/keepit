package com.keepit.scraper

import com.keepit.common.logging.Logging
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.keepit.common.net.URI
import com.keepit.search.{Article, ArticleStore}
import com.keepit.model._
import com.keepit.scraper.extractor.DefaultExtractor
import com.keepit.scraper.extractor.DefaultExtractorFactory
import com.keepit.scraper.extractor.Extractor
import com.keepit.scraper.extractor.YoutubeExtractorFactory
import com.keepit.search.LangDetector
import com.google.inject.Inject
import org.apache.http.HttpStatus
import org.joda.time.Seconds
import play.api.Play.current
import scala.collection.Seq


class OrphanCleaner {

  def cleanNormalizedURIs(readOnly: Boolean = true)(implicit session: RWSession) = {
    val nuriRepo = inject[NormalizedURIRepo]
    val nuris = nuriRepo.allActive()
    val urlRepo = inject[URLRepo]
    var changedNuris = Seq[NormalizedURI]()
    nuris map { nuri =>
      val urls = urlRepo.getByNormUri(nuri.id.get)
      if (urls.isEmpty)
        changedNuris = changedNuris.+:(nuri)
    }
    if (!readOnly) {
      changedNuris map { nuri =>
        nuriRepo.save(nuri.withState(NormalizedURIStates.INACTIVE))
      }
    }
    changedNuris.map(_.id.get)
  }

  def cleanScrapeInfo(readOnly: Boolean = true)(implicit session: RWSession) = {
    val scrapeInfoRepo = inject[ScrapeInfoRepo]
    val sis = scrapeInfoRepo.all() // allActive does the join with nuri. Come up with a better way?
    val nuriRepo = inject[NormalizedURIRepo]
    var oldScrapeInfos = Seq[ScrapeInfo]()
    sis map { si =>
      if (si.state == ScrapeInfoStates.ACTIVE) {
        val nuri = nuriRepo.get(si.uriId)
        if (nuri.state == NormalizedURIStates.INACTIVE)
          oldScrapeInfos = oldScrapeInfos.+:(si)
      }
    }
    if (!readOnly) {
      oldScrapeInfos map { si =>
        scrapeInfoRepo.save(si.withState(ScrapeInfoStates.INACTIVE))
      }
    }
    oldScrapeInfos.map(_.id.get)
  }
}

