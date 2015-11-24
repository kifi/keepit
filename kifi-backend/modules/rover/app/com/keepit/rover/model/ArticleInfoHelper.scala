package com.keepit.rover.model

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model.NormalizedURI
import com.keepit.rover.article.{ Article, ArticleKind }

@Singleton
class ArticleInfoHelper @Inject() (articleInfoRepo: ArticleInfoRepo, articleImageRepo: ArticleImageRepo, airbrake: AirbrakeNotifier) extends Logging {
  def intern(url: String, uriId: Option[Id[NormalizedURI]], kinds: Set[ArticleKind[_ <: Article]])(implicit session: RWSession): Map[ArticleKind[_ <: Article], RoverArticleInfo] = {
    if (kinds.isEmpty) Map.empty[ArticleKind[_ <: Article], RoverArticleInfo]
    else {
      val existingByKind: Map[ArticleKind[_ <: Article], RoverArticleInfo] = articleInfoRepo.getByUrl(url, excludeState = None).map { info => (info.articleKind -> info) }.toMap
      if (existingByKind.exists(_._2.uriId.contains(Id(15508652)))) log.info(s"[RPB-ROVER] existingByKind for url $url = $existingByKind")
      kinds.map { kind =>
        val savedInfo = existingByKind.get(kind) match {
          case Some(articleInfo) if articleInfo.isActive => {
            if (uriId.exists(expectedUriId => !articleInfo.uriId.contains(expectedUriId))) {
              articleInfo.uriId.foreach { invalidUriId =>
                airbrake.notify(s"Fixing ArticleInfo $kind for url $url with inconsistent uriId: expected $uriId, found $invalidUriId.")
              }
              articleInfoRepo.deleteCache(articleInfo)
              articleImageRepo.getByArticleInfo(articleInfo, excludeState = None).foreach(articleImage => articleImageRepo.save(articleImage.copy(uriId = None))) // releasing referential integrity

              val updatedArticleInfo = articleInfoRepo.save(articleInfo.copy(uriId = uriId))
              articleImageRepo.getByArticleInfo(updatedArticleInfo, excludeState = None).foreach(articleImage => articleImageRepo.save(articleImage.copy(uriId = uriId)))

              updatedArticleInfo
            } else {
              articleInfo
            }
          }
          case inactiveArticleInfoOpt => {
            val newInfo = RoverArticleInfo.initialize(url, uriId, kind).copy(id = inactiveArticleInfoOpt.flatMap(_.id))
            articleInfoRepo.save(newInfo)
          }
        }
        kind -> savedInfo
      }.toMap
    }
  }

  def deactivate(url: String, kinds: Set[ArticleKind[_ <: Article]])(implicit session: RWSession): Unit = {
    if (kinds.nonEmpty) {
      articleInfoRepo.getByUrl(url).foreach { info =>
        if (kinds.contains(info.articleKind)) {
          articleInfoRepo.save(info.copy(state = ArticleInfoStates.INACTIVE))
          articleImageRepo.getByArticleInfo(info).foreach(articleImage => articleImageRepo.save(articleImage.copy(state = ArticleImageStates.INACTIVE)))
        }
      }
    }
  }
}
