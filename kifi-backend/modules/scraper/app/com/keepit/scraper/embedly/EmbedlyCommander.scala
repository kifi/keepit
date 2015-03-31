package com.keepit.scraper.embedly

import scala.concurrent.Future

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.db.Id
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import com.keepit.model.NormalizedURI

import play.api.libs.concurrent.Execution.Implicits.defaultContext

@ImplementedBy(classOf[EmbedlyCommanderImpl])
trait EmbedlyCommander {
  def getEmbedlyInfoFromStore(id: Id[NormalizedURI]): Option[EmbedlyInfo]
  def fetchEmbedlyInfo(id: Id[NormalizedURI], url: String): Future[Option[EmbedlyInfo]]
}

class EmbedlyCommanderImpl @Inject() (
    embedlyClient: EmbedlyClient,
    embedlyStore: EmbedlyStore,
    clock: Clock) extends EmbedlyCommander {

  override def getEmbedlyInfoFromStore(id: Id[NormalizedURI]): Option[EmbedlyInfo] = {
    embedlyStore.syncGet(id) map { _.info }
  }

  private def needToCallEmbedly(info: StoredEmbedlyInfo): Boolean = {
    info.calledEmbedlyAt.plusMonths(6).getMillis < clock.now().getMillis
  }

  private def fetchAndPersistEmbedlyInfo(id: Id[NormalizedURI], url: String): Future[Option[EmbedlyInfo]] = {
    val infoFut = embedlyClient.getEmbedlyInfo(url)
    infoFut.map { infoOpt =>
      infoOpt.foreach { info =>
        val storedInfo = StoredEmbedlyInfo(uriId = id, info = info, calledEmbedlyAt = clock.now())
        embedlyStore.+=(id, storedInfo)
      }
      infoOpt
    }
  }

  override def fetchEmbedlyInfo(id: Id[NormalizedURI], url: String): Future[Option[EmbedlyInfo]] = {
    embedlyStore.syncGet(id) match {
      case Some(storedInfo) if (!needToCallEmbedly(storedInfo)) => Future.successful(Some(storedInfo.info))
      case _ => fetchAndPersistEmbedlyInfo(id, url)
    }
  }
}
