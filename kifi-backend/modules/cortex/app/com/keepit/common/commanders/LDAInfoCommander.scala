package com.keepit.common.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.cortex.MiscPrefix
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.dbmodel.{ LDAInfo, LDAInfoRepo }
import com.keepit.cortex.models.lda._
import play.api.libs.json.Json

import scala.collection.mutable

@Singleton
class LDAInfoCommander @Inject() (
    db: Database,
    wordRep: LDAWordRepresenter,
    topicInfoRepo: LDAInfoRepo,
    ldaTopicWords: DenseLDATopicWords) {

  private var currentConfig = getAllConfigs(wordRep.version)

  def ldaConfigurations: LDATopicConfigurations = currentConfig

  val numOfTopics: Int = wordRep.lda.dimension
  val ldaDimMap = mutable.Map(wordRep.version -> wordRep.lda.dimension)

  private def getAllConfigs(version: ModelVersion[DenseLDA]) = {
    val info = db.readOnlyReplica { implicit s => topicInfoRepo.getAllByVersion(version) }

    val updatedInfo = if (info.isEmpty) {
      val dim = wordRep.lda.dimension
      db.readWrite { implicit s =>
        (0 until dim).foreach { i => topicInfoRepo.save(LDAInfo(version = version, dimension = dim, topicId = i)) }
        topicInfoRepo.getAllByVersion(version)
      }
    } else info

    val confMap = updatedInfo.map { case in => (in.topicId.toString, LDATopicConfiguration(in.topicName, in.isActive)) }.toMap
    LDATopicConfigurations(confMap)
  }

  private def saveConfigs(version: ModelVersion[DenseLDA], config: Map[String, LDATopicConfiguration]): Unit = {
    db.readWrite { implicit s =>
      config.foreach {
        case (topic, conf) =>
          val model = topicInfoRepo.getByTopicId(version, topic.toInt)
          topicInfoRepo.save(model.copy(topicName = conf.topicName, isActive = conf.isActive).withUpdateTime(currentDateTime))
      }
    }
  }

  // consumers of lda service might query dim of some previous lda model
  def getLDADimension(version: ModelVersion[DenseLDA]): Int = {
    ldaDimMap.getOrElseUpdate(version,
      db.readOnlyReplica { implicit s => topicInfoRepo.getDimension(version).get }
    )
  }

  val activeTopics = currentConfig.configs.filter { case (id, conf) => conf.isActive }.map { case (id, _) => id.toInt }.toArray.sorted

  def topicConfigs(fromId: Int, toId: Int): Map[String, LDATopicConfiguration] = {
    assume(fromId <= toId && toId < numOfTopics && fromId >= 0)
    (fromId to toId).map { id =>
      id.toString -> currentConfig.configs.getOrElse(id.toString, LDATopicConfiguration.default)
    }.toMap
  }

  def saveConfigEdits(config: Map[String, LDATopicConfiguration]) = {
    val newConfig = LDATopicConfigurations(currentConfig.configs ++ config)
    currentConfig = newConfig
    saveConfigs(wordRep.version, config)
  }

  def topicWords(topicId: Int, topN: Int): Seq[(String, Float)] = {
    assume(topicId >= 0 && topicId < numOfTopics && topN >= 0)

    ldaTopicWords.topicWords(topicId).toArray.sortBy(-1f * _._2).take(topN)
  }

  def topicWords(fromId: Int, toId: Int, topN: Int): Map[Int, Seq[(String, Float)]] = {
    assume(fromId <= toId && toId < numOfTopics && fromId >= 0)

    (fromId to toId).map { id =>
      (id, topicWords(id, topN))
    }.toMap
  }
}
