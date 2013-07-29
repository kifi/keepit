package com.keepit.learning.topicmodel

import com.keepit.search.ArticleStore
import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import scala.collection.mutable.{Map => MutMap}
import com.keepit.common.db.SequenceNumber
import com.google.inject.ImplementedBy
import scala.Option.option2Iterable
import scala.collection.mutable.{Map => MutMap}
import com.google.inject.Singleton
import scala.concurrent._
import ExecutionContext.Implicits.global
import com.keepit.common.zookeeper.CentralConfigKey
import com.keepit.common.zookeeper.StringCentralConfigKey
import com.keepit.common.zookeeper.CentralConfig

@Singleton
class TopicRemodeler @Inject()(
  db: Database,
  uriRepo: NormalizedURIRepo,
  bookmarkRepo: BookmarkRepo,
  articleStore: ArticleStore,
  modelAccessor: SwitchableTopicModelAccessor,
  modelFactory: TopicModelAccessorFactory,
  centralConfig: CentralConfig
) extends TopicUpdater(db, uriRepo, bookmarkRepo, articleStore, modelAccessor, modelFactory, centralConfig){

  val remodelKey = new TopicRemodelKey()

  def remodel(continueFromLastInteruption: Boolean) = {
    def afterRefresh() = {
      if (continueFromLastInteruption && shouldContinueRemodel) {
        log.info("remodelling, continued from last interuption")
      } else {
        log.info("remodelling, from scratch")
        reset(useActive = false)        // wipe out content associated with the inactive model
        centralConfig(new NewModelKey()) = modelAccessor.getInactiveFlag
        centralConfig.update(remodelKey, RemodelState.STARTED)
        log.info("update remodel status to STARTED")
      }

      var catchUp = false
      while (!catchUp) {
        val (m, n) = update(useActive = false)
        if (m.max(n) < fetchSize) catchUp = true
      }
      centralConfig.update(flagKey, modelAccessor.getInactiveFlag)
      centralConfig.update(remodelKey, RemodelState.DONE)
    }

    log.info(s"TopicUpdater: start remodelling ... ")
    refreshInactiveModel()
    afterRefresh()
  }

  private def shouldContinueRemodel = {
    val newModel = centralConfig(new NewModelKey())
    newModel.isDefined && newModel.get != modelAccessor.getCurrentFlag
  }
}
