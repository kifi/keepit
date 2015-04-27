package com.keepit.common.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.dbmodel._
import com.keepit.cortex.models.lda.{ DenseLDA, LDATopic }
import com.keepit.model.{ NormalizedURI, User, Persona }
import com.keepit.shoebox.ShoeboxServiceClient
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits._
import scala.collection.mutable
import scala.math.sqrt

import com.keepit.cortex.utils.MatrixUtils._

import scala.concurrent.Future

@ImplementedBy(classOf[LDAPersonaCommanderImpl])
trait LDAPersonaCommander {
  def getExistingPersonaFeature(personaId: Id[Persona])(implicit version: ModelVersion[DenseLDA]): Option[PersonaLDAFeature]
  def generatePersonaFeature(topicIds: Seq[LDATopic])(implicit version: ModelVersion[DenseLDA]): (UserTopicMean, Int)
  def savePersonaFeature(pid: Id[Persona], feature: UserTopicMean)(implicit version: ModelVersion[DenseLDA]): PersonaLDAFeature
  def getUserPersonaFeatures(userId: Id[User])(implicit version: ModelVersion[DenseLDA]): Future[(Seq[PersonaLDAFeature], Seq[DateTime])]
  def evaluatePersonaFeature(pid: Id[Persona], sampleSize: Int = 50)(implicit version: ModelVersion[DenseLDA]): Map[Id[NormalizedURI], Float]
  def autoLearn(pid: Id[Persona], uriIds: Seq[Id[NormalizedURI]], labels: Seq[Int], rate: Float = 0.1f)(implicit version: ModelVersion[DenseLDA]): Unit
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
      weightedAverage(vecs, weights)
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

  def getUserPersonaFeatures(userId: Id[User])(implicit version: ModelVersion[DenseLDA]): Future[(Seq[PersonaLDAFeature], Seq[DateTime])] = {
    shoebox.getUserActivePersonas(userId).map { active =>
      val feats = active.personas.map { pid => getExistingPersonaFeature(pid).get }
      (feats, active.updatedAt)
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

  // from admin page
  def autoLearn(pid: Id[Persona], uriIds: Seq[Id[NormalizedURI]], labels: Seq[Int], rate: Float = 0.1f)(implicit version: ModelVersion[DenseLDA]): Unit = {
    require(uriIds.size == labels.size)
    val (xs, ys) = {
      (uriIds zip labels).flatMap {
        case (uriId, label) =>
          val featOpt = db.readOnlyReplica { implicit s => uriLDARepo.getActiveByURI(uriId, version) }
          featOpt.map { feat => (feat.feature.get.value, label) }
      }.unzip
    }

    val pfeat = db.readOnlyReplica { implicit s => personaLDARepo.getPersonaFeature(pid, version) }

    if (pfeat.isDefined) {
      val theta = pfeat.get.feature.mean
      val delta = PersonaFeatureTrainer.mini_batch(xs, ys, theta, rate)
      val newThetaUnormalized = add(theta, delta).map { x => x max 0.00001f } // never go negative
      val normalizer = newThetaUnormalized.sum
      val newTheta = newThetaUnormalized.map { _ / normalizer }
      savePersonaFeature(pid, UserTopicMean(newTheta))
    }
  }

}

// simple gradient training based on Hinge-like loss, where Loss function is defined as:
// L(x, theta, label) = {
//    if label = +1, max(0, 0.5 - f(x, theta))    i.e. only apply gradient when f < 0.5
//    if label = -1, max(0, f(x, theta) - 0.5)    i.e. only apply gradient when f > 0.5
// }
// where f(x, theta) = cosine_similarity(x, theta)
object PersonaFeatureTrainer {

  // label: +1 or -1. +1: encourage theta to have greater cosine similarity to x. -1: cosine(x, theta) should be smaller
  // climb along negative gradient to reduce loss.
  def negativeGradient(x: Array[Float], label: Int, theta: Array[Float]): Array[Float] = {
    require(x.size == theta.size)
    require(label == 1 || label == -1)
    val xNorm = sqrt(dot(x, x)).toFloat
    val tNorm = sqrt(dot(theta, theta)).toFloat
    val f = cosineDistance(x, theta)

    // grad = x / (t_norm * x_norm) - t * (f / (t_norm * t_norm))
    val term1 = x.map { _ / (tNorm * xNorm) }
    val term2 = theta.map { _ * f / (tNorm * tNorm) }
    val grad = add(term1, term2.map { -1 * _ })
    grad.map { _ * label }

  }

  def mini_batch(xs: Seq[Array[Float]], labels: Seq[Int], theta: Array[Float], rate: Float): Array[Float] = {
    var change = new Array[Float](theta.size)
    (xs zip labels) foreach {
      case (x, label) =>
        val f = cosineDistance(x, theta)
        if ((f - 0.5) * label > 0) {
          // do nothing. zero gradient
        } else {
          val delta = negativeGradient(x, label, theta)
          change = add(change, delta)
        }
    }
    change.map { rate * _ }
  }
}
