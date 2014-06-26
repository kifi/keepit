package com.keepit.cortex.models.lda

import com.google.inject.{Inject, Singleton}
import com.keepit.common.db.slick.Database
import com.keepit.cortex.dbmodel._
import com.keepit.model.NormalizedURI
import com.keepit.model.UrlHash
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.core.FeatureRepresentation
import com.keepit.model.NormalizedURIStates

trait UpdateAction
object UpdateAction {
  object ComputeFeature extends UpdateAction
  object DeactivateFeature extends UpdateAction
  object Ignore extends UpdateAction
}

@Singleton
class LDADbUpdater @Inject()(
  representer: LDAURIRepresenter,
  db: Database,
  uriRepo: CortexURIRepo,
  topicRepo: URILDATopicRepo
){
  import UpdateAction._

  private val fetchSize = 500

  def update(): Unit = {
    val tasks = fetchTasks
    processTasks(tasks)
  }

  private def fetchTasks(): Seq[CortexURI] = ???

  private def processTasks(uris: Seq[CortexURI]): Unit = {

    uris.foreach{ uri =>


    }

  }

  private def upateAction(uri: CortexURI): UpdateAction = {
    val featOpt = db.readOnly{ implicit s => topicRepo.getFeature(uri.uriId, representer.version)}

    if (featOpt.isDefined && uri.state.value != NormalizedURIStates.SCRAPED.value) DeactivateFeature
    //else if (featOpt.isDefined && )

    null
  }



  private def computeFeature(uri: CortexURI): Option[URILDATopic] = {
    val normUri = NormalizedURI(id = Some(uri.uriId), seq = SequenceNumber[NormalizedURI](uri.seq.value), url = "", urlHash = UrlHash(""))
    val feat = representer(normUri)
    feat.map{toURILDATopic(_)}
  }

  private def toURILDATopic(feat: FeatureRepresentation[NormalizedURI, DenseLDA]): URILDATopic = null


}