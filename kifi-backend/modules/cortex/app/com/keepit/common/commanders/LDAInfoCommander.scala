package com.keepit.common.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.cortex.MiscPrefix
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.dbmodel.{ LDAInfo, LDAInfoRepo }
import com.keepit.cortex.models.lda._
import com.keepit.cortex.PublishingVersions

import scala.collection.mutable

@Singleton
class LDAInfoCommander @Inject() (
    db: Database,
    topicInfoRepo: LDAInfoRepo,
    topicWordsStore: LDATopicWordsStore) {

  val ldaVersion = PublishingVersions.denseLDAVersion // all queries in this commander should query against this version
  private val ldaTopicWords = topicWordsStore.get(MiscPrefix.LDA.topicWordsJsonFile, ldaVersion).get
  private val numOfTopics: Int = ldaTopicWords.topicWords.length
  def getLDADimension(): Int = numOfTopics

  private var currentConfig = getLDAConfigsFromDB(ldaVersion)

  def ldaConfigurations: LDATopicConfigurations = currentConfig

  def getTopicName(topicId: Int): Option[String] = {
    val conf = currentConfig.configs(topicId.toString)
    if (conf.isNameable && conf.topicName != LDAInfo.DEFUALT_NAME) Some(conf.topicName) else None
  }

  val activeTopics = currentConfig.configs.filter { case (id, conf) => conf.isActive }.map { case (id, _) => id.toInt }.toArray.sorted
  val inactiveTopics = currentConfig.configs.filter { case (id, conf) => !conf.isActive }.map { case (id, _) => id.toInt }.toSet

  def topicConfigs(fromId: Int, toId: Int): Map[String, LDATopicConfiguration] = {
    assume(fromId <= toId && toId < numOfTopics && fromId >= 0)
    (fromId to toId).map { id =>
      id.toString -> currentConfig.configs.getOrElse(id.toString, LDATopicConfiguration.default)
    }.toMap
  }

  private def topicWords(topicId: Int, topN: Int): Seq[(String, Float)] = {
    ldaTopicWords.topicWords(topicId).toArray.sortBy(-1f * _._2).take(topN)
  }

  def topicWords(fromId: Int, toId: Int, topN: Int): Map[Int, Seq[(String, Float)]] = {
    assume(fromId <= toId && toId < numOfTopics && fromId >= 0 && topN >= 0)

    (fromId to toId).map { id =>
      (id, topicWords(id, topN))
    }.toMap
  }

  def unamedTopics(limit: Int): (Seq[LDAInfo], Seq[Map[String, Float]]) = {
    val infos = db.readOnlyReplica { implicit s => topicInfoRepo.getUnamed(ldaVersion, limit) }
    val words = infos.map { info => topicWords(topicId = info.topicId, topN = 50).toMap }
    (infos, words)
  }

  // edits from admin
  def saveConfigEdits(config: Map[String, LDATopicConfiguration]) = {

    def saveConfigsToDB(version: ModelVersion[DenseLDA], config: Map[String, LDATopicConfiguration]): Unit = {
      db.readWrite { implicit s =>
        config.foreach {
          case (topic, conf) =>
            val model = topicInfoRepo.getByTopicId(version, topic.toInt)
            topicInfoRepo.save(model.copy(topicName = conf.topicName, isActive = conf.isActive, isNameable = conf.isNameable).withUpdateTime(currentDateTime))
        }
      }
    }

    val newConfig = LDATopicConfigurations(currentConfig.configs ++ config)
    currentConfig = newConfig
    saveConfigsToDB(ldaVersion, config)
  }

  private def getLDAConfigsFromDB(version: ModelVersion[DenseLDA]) = {
    val info = db.readOnlyReplica { implicit s => topicInfoRepo.getAllByVersion(version) }

    val updatedInfo = if (info.isEmpty) {
      val dim = numOfTopics
      db.readWrite { implicit s =>
        (0 until dim).foreach { i => topicInfoRepo.save(LDAInfo(version = version, dimension = dim, topicId = i)) }
        topicInfoRepo.getAllByVersion(version)
      }
    } else info

    val confMap = updatedInfo.map { case in => (in.topicId.toString, LDATopicConfiguration(in.topicName, in.isActive, in.isNameable)) }.toMap
    LDATopicConfigurations(confMap)
  }
}
