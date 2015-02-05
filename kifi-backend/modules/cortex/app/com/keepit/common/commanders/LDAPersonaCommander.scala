package com.keepit.common.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.dbmodel._
import com.keepit.cortex.models.lda.{ DenseLDA, LDATopic }
import com.keepit.cortex.utils.MatrixUtils._
import com.keepit.model.{ NormalizedURI, User, Persona }
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits._
import scala.collection.mutable

import com.keepit.cortex.utils.MatrixUtils.cosineDistance

import scala.concurrent.Future

@ImplementedBy(classOf[LDAPersonaCommanderImpl])
trait LDAPersonaCommander {
  def getExistingPersonaFeature(personaId: Id[Persona])(implicit version: ModelVersion[DenseLDA]): Option[PersonaLDAFeature]
  def generatePersonaFeature(topicIds: Seq[LDATopic])(implicit version: ModelVersion[DenseLDA]): (UserTopicMean, Int)
  def savePersonaFeature(pid: Id[Persona], feature: UserTopicMean)(implicit version: ModelVersion[DenseLDA]): PersonaLDAFeature
  def getUserPersonaFeatures(userId: Id[User])(implicit version: ModelVersion[DenseLDA]): Future[Seq[PersonaLDAFeature]]
  def evaluatePersonaFeature(pid: Id[Persona], sampleSize: Int = 50)(implicit version: ModelVersion[DenseLDA]): Map[Id[NormalizedURI], Float]
}

@Singleton
class LDAPersonaCommanderImpl @Inject() (
    db: Database,
    shoebox: ShoeboxServiceClient,
    info: LDAInfoCommander,
    uriLDARepo: URILDATopicRepo,
    personaLDARepo: PersonaLDAFeatureRepo,
    userLDARepo: UserLDAStatsRepo) extends LDAPersonaCommander {

  // overridable in test
  protected def getLDADimension(implicit version: ModelVersion[DenseLDA]): Int = info.getLDADimension(version)

  def getExistingPersonaFeature(personaId: Id[Persona])(implicit version: ModelVersion[DenseLDA]) = {
    db.readOnlyReplica { implicit s => personaLDARepo.getPersonaFeature(personaId, version) }
  }

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
    val dim = getLDADimension
    require(dim == feature.mean.size, s"trying to save feature vector with size: ${feature.mean.size}, expected dimension: ${dim}")
    db.readWrite { implicit s =>
      val toSave = personaLDARepo.getPersonaFeature(pid, version) match {
        case Some(model) => model.withFeature(feature)
        case None => PersonaLDAFeature.create(pid, version, feature)
      }
      personaLDARepo.save(toSave)
    }
  }

  def getUserPersonaFeatures(userId: Id[User])(implicit version: ModelVersion[DenseLDA]): Future[Seq[PersonaLDAFeature]] = {
    shoebox.getUserActivePersonas(userId).map { peronsas =>
      peronsas.personas.map { pid => getExistingPersonaFeature(pid).get }
    }
  }

  def evaluatePersonaFeature(pid: Id[Persona], sampleSize: Int = 50)(implicit version: ModelVersion[DenseLDA]): Map[Id[NormalizedURI], Float] = {
    val dim = getLDADimension
    val uriScores = mutable.Map[Id[NormalizedURI], Float]()
    val personaFeature = db.readOnlyReplica { implicit s => personaLDARepo.getPersonaFeature(pid, version) }

    if (personaFeature.isEmpty) return Map()

    (0 until dim).map { i =>
      val uriIds = db.readOnlyReplica { implicit s => uriLDARepo.getLatestURIsInTopic(LDATopic(i), version, sampleSize) }.map { _._1 }
      val uriFeats = db.readOnlyReplica { implicit s => uriLDARepo.getActiveByURIs(uriIds, version) }
      uriFeats.foreach { featOpt =>
        featOpt.foreach { feat =>
          feat.feature.foreach { vec =>
            val score = cosineDistance(vec.value, personaFeature.get.feature.mean)
            uriScores += (feat.uriId -> score.toFloat)
          }
        }
      }
    }

    uriScores.toArray.sortBy(-_._2).take(500).toMap
  }

}
