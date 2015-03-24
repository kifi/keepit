package com.keepit.cortex.dbmodel

import com.keepit.common.db.slick._
import com.google.inject.{ ImplementedBy, Provider, Inject, Singleton }
import com.keepit.common.time._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.Id
import com.keepit.model.{ Library, Keep, User, NormalizedURI }
import com.keepit.common.db.State
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.cortex.sql.CortexTypeMappers
import scala.slick.jdbc.StaticQuery
import com.keepit.cortex.models.lda.LDATopic
import com.keepit.cortex.models.lda.SparseTopicRepresentation
import com.keepit.cortex.models.lda.LDATopicFeature
import org.joda.time.DateTime
import scala.collection.mutable

@ImplementedBy(classOf[URILDATopicRepoImpl])
trait URILDATopicRepo extends DbRepo[URILDATopic] {
  def getFeature(uriId: Id[NormalizedURI], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[LDATopicFeature]
  def getByURI(uriId: Id[NormalizedURI], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[URILDATopic]
  def getActiveByURI(uriId: Id[NormalizedURI], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[URILDATopic]
  def getActiveByURIs(uriIds: Seq[Id[NormalizedURI]], version: ModelVersion[DenseLDA])(implicit session: RSession): Seq[Option[URILDATopic]]
  def getHighestSeqNumber(version: ModelVersion[DenseLDA])(implicit session: RSession): SequenceNumber[NormalizedURI]
  def getUpdateTimeAndState(uriId: Id[NormalizedURI], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[(DateTime, State[URILDATopic])]
  def getUserTopicHistograms(userId: Id[User], version: ModelVersion[DenseLDA], after: Option[DateTime] = None)(implicit session: RSession): Seq[(LDATopic, Int)]
  def getSmartRecentUserTopicHistograms(userId: Id[User], version: ModelVersion[DenseLDA], noOlderThan: DateTime, preferablyNewerThan: DateTime, minNum: Int, maxNum: Int)(implicit session: RSession): Seq[(LDATopic, Int)]
  def getLatestURIsInTopic(topicId: LDATopic, version: ModelVersion[DenseLDA], limit: Int)(implicit session: RSession): Seq[(Id[NormalizedURI], Float)]
  def getFeaturesSince(seq: SequenceNumber[NormalizedURI], version: ModelVersion[DenseLDA], limit: Int)(implicit session: RSession): Seq[URILDATopic]
  def countUserURIFeatures(userId: Id[User], version: ModelVersion[DenseLDA], min_num_words: Int)(implicit session: RSession): Int
  def getUserURIFeatures(userId: Id[User], version: ModelVersion[DenseLDA], min_num_words: Int)(implicit session: RSession): Seq[LDATopicFeature]
  def getUserRecentURIFeatures(userId: Id[User], version: ModelVersion[DenseLDA], min_num_words: Int, limit: Int)(implicit session: RSession): Seq[(Id[Keep], Seq[LDATopic], LDATopicFeature)]
  def getTopicCounts(version: ModelVersion[DenseLDA])(implicit session: RSession): Seq[(Int, Int)] // (topic_id, counts)
  def getFirstTopicAndScore(uriId: Id[NormalizedURI], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[(LDATopic, Float)]
  def getLibraryURIFeatures(libId: Id[Library], version: ModelVersion[DenseLDA], min_num_words: Int)(implicit session: RSession): Seq[LDATopicFeature]
  def getURIsByTopics(firstTopic: LDATopic, secondTopic: LDATopic, thirdTopic: LDATopic, version: ModelVersion[DenseLDA], limit: Int)(implicit session: RSession): Seq[Id[NormalizedURI]]
}

@Singleton
class URILDATopicRepoImpl @Inject() (
    val db: DataBaseComponent,
    val keepRepoProvider: Provider[CortexKeepRepo],
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[URILDATopic] with URILDATopicRepo with CortexTypeMappers {

  import db.Driver.simple._

  type RepoImpl = URILDATopicTable

  class URILDATopicTable(tag: Tag) extends RepoTable[URILDATopic](db, tag, "uri_lda_topic") {
    def uriId = column[Id[NormalizedURI]]("uri_id")
    def uriSeq = column[SequenceNumber[NormalizedURI]]("uri_seq")
    def version = column[ModelVersion[DenseLDA]]("version")
    def numOfWords = column[Int]("num_words")
    def firstTopic = column[LDATopic]("first_topic", O.Nullable)
    def secondTopic = column[LDATopic]("second_topic", O.Nullable)
    def thirdTopic = column[LDATopic]("third_topic", O.Nullable)
    def firstTopicScore = column[Float]("first_topic_score", O.Nullable)
    def timesFirstTopicChanged = column[Int]("times_first_topic_changed")
    def sparseFeature = column[SparseTopicRepresentation]("sparse_feature", O.Nullable)
    def feature = column[LDATopicFeature]("feature", O.Nullable)
    def * = (id.?, createdAt, updatedAt, uriId, uriSeq, version, numOfWords, firstTopic.?, secondTopic.?, thirdTopic.?, firstTopicScore.?, timesFirstTopicChanged, sparseFeature.?, feature.?, state) <> ((URILDATopic.apply _).tupled, URILDATopic.unapply _)
  }

  def table(tag: Tag) = new URILDATopicTable(tag)
  initTable()

  def deleteCache(model: URILDATopic)(implicit session: RSession): Unit = {}
  def invalidateCache(model: URILDATopic)(implicit session: RSession): Unit = {}

  def getFeature(uriId: Id[NormalizedURI], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[LDATopicFeature] = {
    val q = for {
      r <- rows
      if (r.uriId === uriId && r.version === version && r.state === URILDATopicStates.ACTIVE)
    } yield r.feature

    q.firstOption
  }

  def getUpdateTimeAndState(uriId: Id[NormalizedURI], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[(DateTime, State[URILDATopic])] = {
    val q = for {
      r <- rows
      if (r.uriId === uriId && r.version === version)
    } yield (r.updatedAt, r.state)

    q.firstOption
  }

  def getByURI(uriId: Id[NormalizedURI], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[URILDATopic] = {
    val q = for {
      r <- rows
      if (r.uriId === uriId && r.version === version)
    } yield r

    q.firstOption
  }

  def getActiveByURIs(uriIds: Seq[Id[NormalizedURI]], version: ModelVersion[DenseLDA])(implicit session: RSession): Seq[Option[URILDATopic]] = {
    val feats = (for {
      r <- rows
      if ((r.uriId inSetBind uriIds) && r.version === version && r.state === URILDATopicStates.ACTIVE)
    } yield r).list

    val m = mutable.Map.empty[Id[NormalizedURI], URILDATopic]
    feats.foreach { feat => m(feat.uriId) = feat }
    uriIds.map { uriId => m.get(uriId) }
  }

  def getActiveByURI(uriId: Id[NormalizedURI], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[URILDATopic] = {
    (for { r <- rows if (r.uriId === uriId && r.version === version && r.state === URILDATopicStates.ACTIVE) } yield r).firstOption
  }

  def getHighestSeqNumber(version: ModelVersion[DenseLDA])(implicit session: RSession): SequenceNumber[NormalizedURI] = {
    import StaticQuery.interpolation

    val sql = sql"select max(uri_seq) from uri_lda_topic where version = ${version.version}"
    SequenceNumber[NormalizedURI](sql.as[Long].first max 0L)
  }

  def getUserTopicHistograms(userId: Id[User], version: ModelVersion[DenseLDA], after: Option[DateTime])(implicit session: RSession): Seq[(LDATopic, Int)] = {
    import StaticQuery.interpolation

    // could be expensive. may revisit this later.

    if (after.isDefined) {
      val q =
        sql"""select tp.first_topic, count(ck.uri_Id) from cortex_keep as ck inner join uri_lda_topic as tp
           on ck.uri_id = tp.uri_id
           where ck.user_id = ${userId.id} and tp.version = ${version.version}
           and ck.state = 'active' and tp.state = 'active' and tp.first_topic is not null and ck.kept_at > ${after.get}
           group by tp.first_topic"""
      q.as[(Int, Int)].list map { case (topic, count) => (LDATopic(topic), count) }
    } else {
      val q =
        sql"""select tp.first_topic, count(ck.uri_Id) from cortex_keep as ck inner join uri_lda_topic as tp
           on ck.uri_id = tp.uri_id
           where ck.user_id = ${userId.id} and tp.version = ${version.version}
           and ck.state = 'active' and tp.state = 'active' and tp.first_topic is not null
           group by tp.first_topic"""
      q.as[(Int, Int)].list map { case (topic, count) => (LDATopic(topic), count) }
    }

  }

  def getSmartRecentUserTopicHistograms(userId: Id[User], version: ModelVersion[DenseLDA], noOlderThan: DateTime, preferablyNewerThan: DateTime, minNum: Int, maxNum: Int)(implicit session: RSession): Seq[(LDATopic, Int)] = {
    import StaticQuery.interpolation
    import scala.slick.jdbc.GetResult

    val q =
      sql"""select tp.first_topic, ck.kept_at from cortex_keep as ck inner join uri_lda_topic as tp
           on ck.uri_id = tp.uri_id
           where ck.user_id = ${userId.id} and tp.version = ${version.version}
           and ck.source = 'keeper' and ck.kept_at > ${noOlderThan} and ck.state = 'active' and tp.state = 'active' and tp.first_topic is not null
           order by ck.kept_at desc limit ${maxNum}"""

    val topicAndDates = q.as[(Int, DateTime)].list
    val sureTake = topicAndDates.take(minNum)
    val rest = topicAndDates.drop(minNum).takeWhile(_._2 > preferablyNewerThan)
    (sureTake ++ rest).groupBy { _._1 }.map { case (topicId, gp) => (LDATopic(topicId), gp.size) }.toSeq
  }

  // admin usage. (uriId, first_topic_score)
  def getLatestURIsInTopic(topicId: LDATopic, version: ModelVersion[DenseLDA], limit: Int)(implicit session: RSession): Seq[(Id[NormalizedURI], Float)] = {
    import StaticQuery.interpolation

    val q = sql"select uri_id, first_topic_score from uri_lda_topic where first_topic = ${topicId.index} and version = ${version.version} and state = 'active' order by updated_at desc limit ${limit}"
    q.as[(Long, Float)].list.map { case (id, score) => (Id[NormalizedURI](id), score) }
  }

  def getFeaturesSince(seq: SequenceNumber[NormalizedURI], version: ModelVersion[DenseLDA], limit: Int)(implicit session: RSession): Seq[URILDATopic] = {
    val q = (for { r <- rows if (r.uriSeq > seq && r.version === version) } yield r).sortBy(_.uriSeq).take(limit)
    q.list
  }

  def countUserURIFeatures(userId: Id[User], version: ModelVersion[DenseLDA], min_num_words: Int)(implicit session: RSession): Int = {
    import StaticQuery.interpolation
    val q = sql"""select count(ck.uri_id) from cortex_keep as ck inner join uri_lda_topic as tp on ck.uri_id = tp.uri_id where ck.user_id = ${userId.id} and tp.version = ${version.version} and ck.state = 'active' and ck.source != 'default' and tp.state = 'active' and tp.num_words > ${min_num_words}"""
    q.as[Int].list.head
  }

  def getUserURIFeatures(userId: Id[User], version: ModelVersion[DenseLDA], min_num_words: Int)(implicit session: RSession): Seq[LDATopicFeature] = {
    import StaticQuery.interpolation
    import scala.slick.jdbc.GetResult
    implicit val getLDATopicFeature = getResultFromMapper[LDATopicFeature]

    val q = sql"""select tp.feature from cortex_keep as ck inner join uri_lda_topic as tp on ck.uri_id = tp.uri_id where ck.user_id = ${userId.id} and ck.state = 'active' and tp.version = ${version.version} and ck.source != 'default' and tp.state = 'active' and tp.num_words > ${min_num_words}"""
    q.as[LDATopicFeature].list
  }

  def getUserRecentURIFeatures(userId: Id[User], version: ModelVersion[DenseLDA], min_num_words: Int, limit: Int)(implicit session: RSession): Seq[(Id[Keep], Seq[LDATopic], LDATopicFeature)] = {
    import StaticQuery.interpolation
    implicit val getLibraryFeature = getResultFromMapper[LDATopicFeature]
    val q =
      sql"""select ck.keep_id, ck.uri_id, tp.first_topic, tp.second_topic, tp.third_topic, tp.feature from cortex_keep as ck inner join uri_lda_topic as tp
           on ck.uri_id = tp.uri_id
           where ck.user_id = ${userId.id} and ck.state = 'active' and tp.version = ${version.version}
           and ck.source = 'keeper' and tp.state = 'active' and tp.num_words > ${min_num_words}
           order by ck.kept_at desc limit ${limit}"""

    val dups = q.as[(Long, Long, Short, Short, Short, LDATopicFeature)].list.map { case (keepId, uriId, first, second, third, feature) => (Id[Keep](keepId), Id[NormalizedURI](uriId), List(first, second, third).map { LDATopic(_) }, feature) }
    // (user, uri) pairs may not be unique. Dedup and preserve the order.
    val uriIdSet = mutable.Set.empty[Id[NormalizedURI]]
    val dedup = dups.flatMap {
      case (keepId, uriId, topics, feature) =>
        if (!uriIdSet.contains(uriId)) {
          uriIdSet.add(uriId)
          Some((keepId, topics, feature))
        } else None
    }
    dedup
  }

  def getTopicCounts(version: ModelVersion[DenseLDA])(implicit session: RSession): Seq[(Int, Int)] = {
    import StaticQuery.interpolation
    val q = sql"""select tp.first_topic, count(tp.uri_id) from uri_lda_topic as tp where tp.version = ${version.version} and tp.state = 'active' group by tp.first_topic"""
    q.as[(Int, Int)].list
  }

  def getFirstTopicAndScore(uriId: Id[NormalizedURI], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[(LDATopic, Float)] = {
    (for { r <- rows if r.version === version && r.uriId === uriId && r.state === URILDATopicStates.ACTIVE } yield (r.firstTopic, r.firstTopicScore)).firstOption
  }

  def getLibraryURIFeatures(libId: Id[Library], version: ModelVersion[DenseLDA], min_num_words: Int)(implicit session: RSession): Seq[LDATopicFeature] = {
    import StaticQuery.interpolation
    import scala.slick.jdbc.GetResult
    implicit val getLDATopicFeature = getResultFromMapper[LDATopicFeature]

    val q =
      sql"""select tp.feature from cortex_keep as ck inner join uri_lda_topic as tp
           on ck.uri_id = tp.uri_id
           where ck.library_id = ${libId.id} and ck.state = 'active' and tp.version = ${version.version}
           and tp.state = 'active' and tp.num_words > ${min_num_words}"""
    q.as[LDATopicFeature].list
  }

  def getURIsByTopics(firstTopic: LDATopic, secondTopic: LDATopic, thirdTopic: LDATopic, version: ModelVersion[DenseLDA], limit: Int)(implicit session: RSession): Seq[Id[NormalizedURI]] = {
    (for {
      r <- rows
      if r.firstTopic === firstTopic && r.secondTopic === secondTopic && r.thirdTopic === thirdTopic && r.version === version && r.state === URILDATopicStates.ACTIVE
    } yield r.uriId
    ).list.take(limit)
  }
}
