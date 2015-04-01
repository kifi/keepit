package com.keepit.common.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.cortex.{ ModelVersions, MiscPrefix }
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.dbmodel.{ LDAInfo, LDAInfoRepo }
import com.keepit.cortex.models.lda._

import scala.collection.mutable

@Singleton
class LDAInfoCommander @Inject() (
    db: Database,
    topicInfoRepo: LDAInfoRepo,
    topicWordsStore: LDATopicWordsStore) {

  private val availableVersions = ModelVersions.availableLDAVersions
  private val topicWordsCommander = LDATopicWordsCommander(topicWordsStore, availableVersions: _*)
  private val topicConfsCommander = LDAConfigCommander(db, topicInfoRepo, topicWordsCommander, availableVersions: _*)

  val activeTopics = topicConfsCommander.activeTopics
  val inactiveTopics = topicConfsCommander.inactiveTopics

  private val pmiScores = {
    val map = mutable.Map.empty[ModelVersion[DenseLDA], Option[Array[Float]]]
    availableVersions.foreach { version =>
      val infos = db.readOnlyReplica { implicit s => topicInfoRepo.getAllByVersion(version) }
      val scores = infos.sortBy(_.topicId).map { _.pmiScore }.collect { case Some(pmi) => pmi }
      assert(scores.isEmpty || scores.size == infos.size, "pmi score is either defined for every topic, or defined for non")
      if (scores.isEmpty) {
        map(version) = None
      } else {
        map(version) = Some(scores.toArray)
      }
    }
    map
  }

  def getPMIScore(topicId: Int)(implicit version: ModelVersion[DenseLDA]): Option[Float] = {
    pmiScores.get(version).flatten.map { arr => arr(topicId) }
  }

  def getLDADimension(implicit version: ModelVersion[DenseLDA]): Int = topicWordsCommander.getLDADimension(version)

  def topicWords(fromId: Int, toId: Int, topN: Int)(implicit version: ModelVersion[DenseLDA]): Map[Int, Seq[(String, Float)]] = topicWordsCommander.topicWords(fromId, toId, topN)

  def ldaConfigurations(implicit version: ModelVersion[DenseLDA]): LDATopicConfigurations = topicConfsCommander.ldaConfigurations

  def getTopicName(topicId: Int)(implicit version: ModelVersion[DenseLDA]): Option[String] = topicConfsCommander.getTopicName(topicId)

  def topicConfigs(fromId: Int, toId: Int)(implicit version: ModelVersion[DenseLDA]): Map[String, LDATopicConfiguration] = topicConfsCommander.topicConfigs(fromId, toId)

  def unamedTopics(limit: Int)(implicit version: ModelVersion[DenseLDA]): (Seq[LDAInfo], Seq[Map[String, Float]]) = {
    val infos = db.readOnlyReplica { implicit s => topicInfoRepo.getUnamed(version, limit) }
    val words = infos.map { info => topicWordsCommander.topicWords(topicId = info.topicId, topN = 50).toMap }
    (infos, words)
  }

  // edits from admin
  def saveConfigEdits(config: Map[String, LDATopicConfiguration])(implicit version: ModelVersion[DenseLDA]) = topicConfsCommander.saveConfigEdits(config)

  def savePMIScores(pmis: Array[Float])(version: ModelVersion[DenseLDA]): Unit = {
    val infos = db.readOnlyReplica { implicit s => topicInfoRepo.getAllByVersion(version) }
    assert(infos.size == pmis.size, s"pmi score array should match topic model dimension. version = ${version}, get ${pmis.size}, expect ${infos.size}")
    db.readWrite { implicit s =>
      infos.foreach { info =>
        topicInfoRepo.save(info.copy(pmiScore = Some(pmis(info.topicId))))
      }
    }
  }
}

case class LDATopicWordsCommander(topicWordsStore: LDATopicWordsStore, versions: ModelVersion[DenseLDA]*) {

  private val ldaTopicWords = versions.map { version => version -> topicWordsStore.syncGet(MiscPrefix.LDA.topicWordsJsonFile, version).get }.toMap
  private val numTopics = ldaTopicWords.map { case (version, words) => version -> words.topicWords.length }

  def getLDADimension(implicit version: ModelVersion[DenseLDA]): Int = numTopics(version)

  def topicWords(topicId: Int, topN: Int)(implicit version: ModelVersion[DenseLDA]): Seq[(String, Float)] = {
    ldaTopicWords(version).topicWords(topicId).toArray.sortBy(-1f * _._2).take(topN)
  }

  def topicWords(fromId: Int, toId: Int, topN: Int)(implicit version: ModelVersion[DenseLDA]): Map[Int, Seq[(String, Float)]] = {
    assume(fromId <= toId && toId < numTopics(version) && fromId >= 0 && topN >= 0)

    (fromId to toId).map { id => (id, topicWords(id, topN)) }.toMap
  }
}

case class LDAConfigCommander(db: Database, topicInfoRepo: LDAInfoRepo, topicWordsCommander: LDATopicWordsCommander, versions: ModelVersion[DenseLDA]*) {

  private val currentConfigs: mutable.Map[ModelVersion[DenseLDA], LDATopicConfigurations] = {
    val confs = mutable.Map.empty[ModelVersion[DenseLDA], LDATopicConfigurations]
    versions.foreach { version =>
      val conf = getLDAConfigsFromDB(version)
      confs(version) = conf
    }
    confs
  }

  // recent admin changes will not be synced here (performance reasons)
  val activeTopics: Map[ModelVersion[DenseLDA], Array[Int]] = currentConfigs.map {
    case (version, config) =>
      val active = config.configs.filter { case (id, conf) => conf.isActive }.map { case (id, _) => id.toInt }.toArray.sorted
      version -> active
  }.toMap

  // recent admin changes will not be synced here (performance reasons)
  val inactiveTopics: Map[ModelVersion[DenseLDA], Set[Int]] = currentConfigs.map {
    case (version, config) =>
      val inactive = config.configs.filter { case (id, conf) => !conf.isActive }.map { case (id, _) => id.toInt }.toSet
      version -> inactive
  }.toMap

  def ldaConfigurations(implicit version: ModelVersion[DenseLDA]): LDATopicConfigurations = currentConfigs(version)

  def getTopicName(topicId: Int)(implicit version: ModelVersion[DenseLDA]): Option[String] = {
    val conf = currentConfigs(version).configs(topicId.toString)
    if (version.version < 3 && conf.isNameable && conf.topicName != LDAInfo.DEFUALT_NAME) Some(conf.topicName) else None
  }

  def topicConfigs(fromId: Int, toId: Int)(implicit version: ModelVersion[DenseLDA]): Map[String, LDATopicConfiguration] = {
    assume(fromId <= toId && toId < topicWordsCommander.getLDADimension(version) && fromId >= 0)
    (fromId to toId).map { id =>
      id.toString -> currentConfigs(version).configs.getOrElse(id.toString, LDATopicConfiguration.default)
    }.toMap
  }

  // edits from admin
  def saveConfigEdits(config: Map[String, LDATopicConfiguration])(implicit version: ModelVersion[DenseLDA]) = {

    def saveConfigsToDB(version: ModelVersion[DenseLDA], config: Map[String, LDATopicConfiguration]): Unit = {
      db.readWrite { implicit s =>
        config.foreach {
          case (topic, conf) =>
            val model = topicInfoRepo.getByTopicId(version, topic.toInt)
            topicInfoRepo.save(model.copy(topicName = conf.topicName, isActive = conf.isActive, isNameable = conf.isNameable).withUpdateTime(currentDateTime))
        }
      }
    }

    val newConfig = LDATopicConfigurations(currentConfigs(version).configs ++ config)
    currentConfigs(version) = newConfig
    saveConfigsToDB(version, config)
  }

  // will create a default config if not found in DB
  private def getLDAConfigsFromDB(version: ModelVersion[DenseLDA]) = {
    val info = db.readOnlyReplica { implicit s => topicInfoRepo.getAllByVersion(version) }

    val updatedInfo = if (info.isEmpty) {
      val dim = topicWordsCommander.getLDADimension(version)
      db.readWrite { implicit s =>
        (0 until dim).foreach { i => topicInfoRepo.save(LDAInfo(version = version, dimension = dim, topicId = i)) }
        topicInfoRepo.getAllByVersion(version)
      }
    } else info

    val confMap = updatedInfo.map { case in => (in.topicId.toString, LDATopicConfiguration(in.topicName, in.isActive, in.isNameable)) }.toMap
    LDATopicConfigurations(confMap)
  }

}
