package com.keepit.cortex

import com.keepit.FortyTwoGlobal
import com.keepit.common.cache.{ FortyTwoCachePlugin, InMemoryCachePlugin }
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.cortex.models.lda.{ LDADbUpdatePlugin, DenseLDATopicWords, LDAURIFeatureUpdatePlugin }
import com.keepit.cortex.models.word2vec.RichWord2VecURIFeatureUpdatePlugin
import com.keepit.cortex.nlp.POSTagger
import play.api.Application
import play.api.Mode.Prod
import com.keepit.cortex.dbmodel.CortexDataIngestionPlugin

object CortexGlobal extends FortyTwoGlobal(Prod) with CortexServices {
  val module = CortexProdModule()

  override def onStart(app: Application) {
    log.info("starting cortex")
    startCortexServices()
    super.onStart(app)
    log.info("cortex started")
  }
}

trait CortexServices { self: FortyTwoGlobal =>
  def startCortexServices() {
    require(injector.instance[HealthcheckPlugin] != null)
    require(injector.instance[FortyTwoCachePlugin] != null)
    require(injector.instance[InMemoryCachePlugin] != null)
    require(injector.instance[LDADbUpdatePlugin] != null)
    require(injector.instance[RichWord2VecURIFeatureUpdatePlugin] != null)
    require(injector.instance[DenseLDATopicWords] != null)
    require(injector.instance[CortexDataIngestionPlugin] != null)
    require(POSTagger.enabled)
  }
}
