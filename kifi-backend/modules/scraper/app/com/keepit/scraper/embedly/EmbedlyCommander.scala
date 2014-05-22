package com.keepit.scraper.embedly

import scala.concurrent.Future

import com.google.inject.{ImplementedBy, Inject}
import com.keepit.common.db.Id
import com.keepit.common.time.{Clock, DEFAULT_DATE_TIME_ZONE}
import com.keepit.model.NormalizedURI

import play.api.libs.concurrent.Execution.Implicits.defaultContext


@ImplementedBy(classOf[EmbedlyCommanderImpl])
trait EmbedlyCommander {
  def getEmbedlyInfo(id: Id[NormalizedURI], url: String): Future[Option[ExtendedEmbedlyInfo]]
  def fetchEmbedlyInfoIfNecessary(id: Id[NormalizedURI], url: String): Future[Option[ExtendedEmbedlyInfo]]
}

class EmbedlyCommanderImpl @Inject()(
  embedlyClient: EmbedlyClient,
  embedlyStore: EmbedlyStore,
  clock: Clock
) extends EmbedlyCommander {

  def getEmbedlyInfo(id: Id[NormalizedURI], url: String): Future[Option[ExtendedEmbedlyInfo]] = {
    getStoredEmbedlyInfo(id) match {
      case Some(storedInfo) => Future.successful(Some(storedInfo.info))
      case _ => fetchAndPersistEmbedlyInfo(id, url)
    }
  }

  private def getStoredEmbedlyInfo(id: Id[NormalizedURI]): Option[StoredExtendedEmbedlyInfo] = {
    embedlyStore.get(id)
  }

  private def needToCallEmbedly(info: StoredExtendedEmbedlyInfo): Boolean = {
    info.calledEmbedlyAt.plusMonths(6).getMillis < clock.now().getMillis
  }

  private def fetchAndPersistEmbedlyInfo(id: Id[NormalizedURI], url: String): Future[Option[ExtendedEmbedlyInfo]] = {
    val infoFut = embedlyClient.getExtendedEmbedlyInfo(url)
    infoFut.map{ infoOpt =>
      infoOpt.foreach{ info =>
        val storedInfo = StoredExtendedEmbedlyInfo(uriId = id, info = info, calledEmbedlyAt = clock.now())
        embedlyStore.+=(id, storedInfo)
      }
      infoOpt
    }
  }

  def fetchEmbedlyInfoIfNecessary(id: Id[NormalizedURI], url: String): Future[Option[ExtendedEmbedlyInfo]] = {
    getStoredEmbedlyInfo(id) match {
      case Some(storedInfo) if (! needToCallEmbedly(storedInfo)) => Future.successful(Some(storedInfo.info))
      case _ => fetchAndPersistEmbedlyInfo(id, url)
    }
  }
}
