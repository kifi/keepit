package com.keepit.rover.model

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.NormalizedURI
import com.keepit.rover.article.{ Article, ArticleKind }

@Singleton
class ArticleInfoHelper @Inject() (articleInfoRepo: ArticleInfoRepo, articleImageRepo: ArticleImageRepo, airbrake: AirbrakeNotifier) {
  def intern(url: String, uriId: Id[NormalizedURI], kinds: Set[ArticleKind[_ <: Article]])(implicit session: RWSession): Map[ArticleKind[_ <: Article], RoverArticleInfo] = {
    if (kinds.isEmpty) Map.empty[ArticleKind[_ <: Article], RoverArticleInfo]
    else {
      val existingByKind: Map[ArticleKind[_ <: Article], RoverArticleInfo] = articleInfoRepo.getByUrl(url, excludeState = None).map { info => (info.articleKind -> info) }.toMap
      kinds.map { kind =>
        val savedInfo = existingByKind.get(kind) match {
          case Some(articleInfo) if articleInfo.isActive => {
            if (articleInfo.uriId == uriId) articleInfo else {
              airbrake.notify(s"Found ArticleInfo $kind for url $url with inconsistent uriId: expected $uriId, found ${articleInfo.uriId}.")
              articleInfoRepo.deleteCache(articleInfo)
              articleImageRepo.getByArticleInfo(articleInfo, excludeState = None).foreach(articleImage => articleImageRepo.save(articleImage.copy(uriId = uriId)))
              articleInfoRepo.save(articleInfo.copy(uriId = uriId))
            }
          }

          case Some(inactiveArticleInfo) => {
            val reactivatedInfo = inactiveArticleInfo.clean.copy(state = ArticleInfoStates.ACTIVE, uriId = uriId).initializeSchedulingPolicy
            articleInfoRepo.save(reactivatedInfo)
          }
          case None => {
            val newInfo = RoverArticleInfo.initialize(uriId, url, kind)
            articleInfoRepo.save(newInfo)
          }
        }
        kind -> savedInfo
      }.toMap
    }
  }

  def deactivate(uriId: Id[NormalizedURI], kinds: Set[ArticleKind[_ <: Article]])(implicit session: RWSession): Unit = {
    if (kinds.nonEmpty) {
      articleInfoRepo.getByUri(uriId).foreach { info =>
        if (kinds.contains(info.articleKind)) {
          articleInfoRepo.save(info.copy(state = ArticleInfoStates.INACTIVE))
          articleImageRepo.getByArticleInfo(info).foreach(articleImage => articleImageRepo.save(articleImage.copy(state = ArticleImageStates.INACTIVE)))
        }
      }
    }
  }
}
