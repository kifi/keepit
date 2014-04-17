package com.keepit.cortex

import com.keepit.FortyTwoGlobal
import play.api.Mode._
import play.api._
import com.keepit.cortex.models.lda.LDAURIFeatureUpdatePlugin
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.cache.InMemoryCachePlugin
import com.keepit.common.cache.FortyTwoCachePlugin
import com.keepit.cortex.nlp.POSTagger
import com.keepit.cortex.models.word2vec.Word2Vec

object CortexGlobal extends FortyTwoGlobal(Prod) with CortexServices{
  val module = CortexProdModule()

  override def onStart(app: Application) {
    log.info("starting cortex")
    startCortexServices()
    super.onStart(app)
    log.info("cortex started")
  }
}

trait CortexServices { self: FortyTwoGlobal =>
  def startCortexServices(){
    require(injector.instance[HealthcheckPlugin] != null)
    require(injector.instance[FortyTwoCachePlugin] != null)
    require(injector.instance[InMemoryCachePlugin] != null)
    require(injector.instance[LDAURIFeatureUpdatePlugin] != null)
    require(injector.instance[Word2Vec] != null)
    require(POSTagger.enabled)
  }
}
