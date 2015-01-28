package com.keepit.common.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.dbmodel._
import com.keepit.cortex.models.lda.{ LDAUserURIInterestScore, LDAUserURIInterestScores, DenseLDA, LDATopic }
import com.keepit.cortex.utils.MatrixUtils._
import com.keepit.model.{ NormalizedURI, User, Persona }

@ImplementedBy(classOf[LDAPersonaCommanderImpl])
trait LDAPersonaCommander {
  def generatePersonaFeature(topicIds: Seq[LDATopic])(implicit version: ModelVersion[DenseLDA]): (UserTopicMean, Int)
  def savePersonaFeature(pid: Id[Persona], feature: UserTopicMean)(implicit version: ModelVersion[DenseLDA]): PersonaLDAFeature
}

@Singleton
class LDAPersonaCommanderImpl @Inject() (
    db: Database,
    info: LDAInfoCommander,
    personaLDARepo: PersonaLDAFeatureRepo,
    userLDARepo: UserLDAStatsRepo) extends LDAPersonaCommander {

  // overridable in test
  protected def getLDADimension(implicit version: ModelVersion[DenseLDA]): Int = info.getLDADimension(version)

  // admin operation. not optimized for performance
  def generatePersonaFeature(topicIds: Seq[LDATopic])(implicit version: ModelVersion[DenseLDA]): (UserTopicMean, Int) = {
    val dim = getLDADimension
    val vecs = topicIds.distinct.flatMap { tid =>
      val topicVecs = db.readOnlyReplica { implicit s => userLDARepo.getByTopic(version, tid) }
      topicVecs.flatMap { case model => model.userTopicMean.map { _.mean } }
    }

    val feature: Array[Float] = if (vecs.size > 0) {
      val weights = Array.tabulate(vecs.size) { i => 1f / vecs.size }
      weightedAverage(vecs.map { toDoubleArray(_) }, weights)
    } else {
      new Array[Float](dim)
    }

    (UserTopicMean(feature), vecs.size)
  }

  def savePersonaFeature(pid: Id[Persona], feature: UserTopicMean)(implicit version: ModelVersion[DenseLDA]): PersonaLDAFeature = {
    db.readWrite { implicit s =>
      val toSave = personaLDARepo.getPersonaFeature(pid, version) match {
        case Some(model) => model.withFeature(feature)
        case None => PersonaLDAFeature.create(pid, version, feature)
      }
      personaLDARepo.save(toSave)
    }
  }
}
