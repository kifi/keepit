package com.keepit.search

import com.keepit.common.db.ExternalId
import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.model.NormalizedURI
import com.keepit.search.query.LuceneScoreNames
import com.google.inject.{ Inject, Singleton }
import com.keepit.model.SocialConnectionRepo
import com.keepit.common.db.slick.Database
import scala.util.Try
import scala.math._
import com.keepit.model.UserRepo
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.graph.URIGraph
import org.apache.lucene.search.Query
import com.keepit.search.index.PersonalizedSearcher
import com.keepit.search.query.LuceneExplanationExtractor
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import com.keepit.common.time._
import scala.collection.mutable.Map
import com.keepit.search.query.IdSetFilter
import org.apache.lucene.search.FilteredQuery
import com.keepit.common.analytics.MongoEventStore
import com.keepit.common.analytics.MongoSelector
import com.keepit.common.analytics.EventFamilies
import com.keepit.serializer.EventSerializer
import com.keepit.serializer.SearchResultInfoSerializer
import com.keepit.common.logging.Logging

case class BasicQueryInfo(
  queryUUID: ExternalId[ArticleSearchResultRef],
  queryString: String,
  userId: Id[User])

// kifi uri only
case class UriInfo(
  uriId: Id[NormalizedURI],
  textScore: Float,
  bookmarkScore: Float, // use raw score only. Boosted score depends on SearchConfig, which varies from time to time.
  recencyScore: Float,
  clickBoost: Float,
  isMyBookmark: Boolean, // myBookmark is a.k.a. my keep
  isPrivate: Boolean, // private for this user
  friendsKeepsCount: Int, // num of keeps of this uri among my friends
  totalCounts: Int // num of keeps of this uri in entire index
  )



// get this from EventListener. kifi uri only
case class UriLabel(
  clicked: Boolean,
  isCorrectlyRanked: Boolean    // this is false only in this scenario: if this uri was shown to user, AND not clicked by user, AND user clicked some url from google, AND that url is indexed by KiFi.
)

case class SearchResultInfo(
  myHits: Int,
  friendsHits: Int,
  othersHits: Int,
  svVariance: Float,
  svExistenceVar: Float)

// the structure may change a lot in the future,
// as new queries are added and old queries are discarded.
case class LuceneScores(
  multiplicativeBoost: Float,
  additiveBoost: Float,
  percentMatch: Float,
  semanticVector: Float,
  phraseProximity: Float)

case class SearchStatistics(
  basicQueryInfo: BasicQueryInfo,
  uriInfo: UriInfo,
  uriLabel: UriLabel,
  searchResultInfo: SearchResultInfo,
  luceneScores: LuceneScores)


@Singleton
class SearchStatisticsExtractorFactory @Inject() (
  db: Database, userRepo: UserRepo, socialConnectionRepo: SocialConnectionRepo, uriGraph: URIGraph,
  articleIndexer: ArticleIndexer, searchConfigManager: SearchConfigManager, mainSearcherFactory: MainSearcherFactory, parserFactory: MainQueryParserFactory,
  browsingHistoryTracker: BrowsingHistoryTracker, clickHistoryTracker: ClickHistoryTracker, resultClickTracker: ResultClickTracker, store: MongoEventStore){

  def apply(queryUUID: ExternalId[ArticleSearchResultRef],
  queryString: String, userId: Id[User], uriLabelMap: scala.collection.immutable.Map[Id[NormalizedURI], UriLabel]) = {

    new SearchStatisticsExtractor (queryUUID, queryString, userId, uriLabelMap,
  db, userRepo, socialConnectionRepo, uriGraph, articleIndexer, searchConfigManager, mainSearcherFactory, parserFactory,
  browsingHistoryTracker, clickHistoryTracker, resultClickTracker, store)
  }
}

// uriLabelMap contains the uris of interest
class SearchStatisticsExtractor (queryUUID: ExternalId[ArticleSearchResultRef],
  queryString: String, userId: Id[User], uriLabelMap: scala.collection.immutable.Map[Id[NormalizedURI], UriLabel],
  db: Database, userRepo: UserRepo, socialConnectionRepo: SocialConnectionRepo, uriGraph: URIGraph,
  articleIndexer: ArticleIndexer, searchConfigManager: SearchConfigManager, mainSearcherFactory: MainSearcherFactory, parserFactory: MainQueryParserFactory,
  browsingHistoryTracker: BrowsingHistoryTracker, clickHistoryTracker: ClickHistoryTracker, resultClickTracker: ResultClickTracker, store: MongoEventStore) extends Logging{

  val searcher = new SearchStatisticsHelperSearcher(queryString, userId, uriLabelMap.keySet.toSeq, db, userRepo, socialConnectionRepo, uriGraph,
      articleIndexer, searchConfigManager, mainSearcherFactory, parserFactory,
      browsingHistoryTracker, clickHistoryTracker, resultClickTracker)

  private def getLuceneExplain(uriId: Id[NormalizedURI]) = {
    log.info("getting lucene explanation for uriId: " + uriId.id)
    searcher.parsedQuery.map{ query =>
      val personalizedSearcher = searcher.getPersonalizedSearcher(query)
      personalizedSearcher.setSimilarity(searcher.similarity)
      personalizedSearcher.explain(query, uriId.id)
    }
  }

  val uriInfoMap = searcher.getUriInfo

  private def getBasicQueryInfo = BasicQueryInfo(queryUUID, queryString, userId)

  private def getUriInfo(uriId: Id[NormalizedURI]) = uriInfoMap(uriId)

  private def getUriLabel(uriId: Id[NormalizedURI]) = uriLabelMap(uriId)

  private def getSearchResultInfo = {
     val q = MongoSelector(EventFamilies.SERVER_SEARCH).withEventName("search_return_hits").withMetaData("queryUUID", queryUUID.id.toString)

      val searchResultInfo = store.find(q).map{ dbo =>
        val data = EventSerializer.eventSerializer.mongoReads(dbo).get.metaData
        val json = (data.metaData \ "searchResultInfo")
        SearchResultInfoSerializer.serializer.reads(json).get
      }
      if (searchResultInfo.nonEmpty) searchResultInfo.next else SearchResultInfo(-1, -1, -1, -1.0f, -1.0f)
  }

  private def getLuceneScores(uriId: Id[NormalizedURI]) = {
    val explain = getLuceneExplain(uriId)
    explain match {
      case Some(e) => {
        val scores = LuceneExplanationExtractor.extractNamedScores(e)
        LuceneScores(scores.getOrElse(LuceneScoreNames.MULTIPLICATIVE_BOOST, -1.0f),
          scores.getOrElse(LuceneScoreNames.ADDITIVE_BOOST, -1.0f),
          scores.getOrElse(LuceneScoreNames.PERCENT_MATCH, -1.0f),
          scores.getOrElse(LuceneScoreNames.SEMANTIC_VECTOR, -1.0f),
          scores.getOrElse(LuceneScoreNames.PHRASE_PROXIMITY, -1.0f))
      }
      case None => LuceneScores(-1.0f, -1.0f, -1.0f, -1.0f, -1.0f)
    }
  }

  def getSearchStatistics(uriIds: Set[Id[NormalizedURI]]) = {
    var ssMap = Map.empty[Id[NormalizedURI], SearchStatistics]
    val basicQueryInfo = getBasicQueryInfo
    val searchResultInfo = getSearchResultInfo
    uriIds.foreach{ uriId =>
        val uriInfo = getUriInfo(uriId)
        val uriLabel = getUriLabel(uriId)
        val luceneScores = getLuceneScores(uriId)
        val ss = SearchStatistics(basicQueryInfo, uriInfo, uriLabel, searchResultInfo, luceneScores)
        ssMap += uriId -> ss
    }
    ssMap
  }
}


object TrainingDataLabeler {
  private val topNkifi = 2      // at most the top 2 kifi results would be labeled as negative samples

  def getLabeledData(kifiClicked: Seq[Id[NormalizedURI]], googleClicked: Seq[Id[NormalizedURI]], kifiShown: Seq[Id[NormalizedURI]]) = {
    var data = Map.empty[Id[NormalizedURI], (Boolean, Boolean)]         // (isPositive, isCorrectlyRanked)
    if (kifiClicked.nonEmpty) {
      kifiClicked.foreach( uri => data += uri -> (true, true))
    } else {
      val isCorrectlyRanked = googleClicked.isEmpty || googleClicked.exists(uri => kifiShown.contains(uri))         // true if the clicked google uri is not indexed, or it was shown to the user
      kifiShown.take(topNkifi).foreach( uri => if (!googleClicked.contains(uri)) data += uri -> (false, isCorrectlyRanked))
    }
    data
  }
}


/**
 * many codes in this class are taken from MainSearcher and ExtSearchController.
 * Unfortunately, we have to do some duplicated computations.
 * Ideally, we would like to reuse the resources constructed in MainSearcher or
 * ExtSearchController. However, when user is typing in the search bar, extSearchController
 * may generate several searchers, therefore resources generated in those "temporary" searchers
 * may not be desirable.
 *
 * One difference from the mainSearcher: we only score a few documents (those we want to collect as labeled training samples)
 *
 * TODO: more elegant solution ?
 */
class SearchStatisticsHelperSearcher (queryString: String, userId: Id[User], targetUriIds: Seq[Id[NormalizedURI]],
  db: Database, userRepo: UserRepo, socialConnectionRepo: SocialConnectionRepo, uriGraph: URIGraph,
  articleIndexer: ArticleIndexer, searchConfigManager: SearchConfigManager, mainSearcherFactory: MainSearcherFactory, parserFactory: MainQueryParserFactory,
  browsingHistoryTracker: BrowsingHistoryTracker, clickHistoryTracker: ClickHistoryTracker, resultClickTracker: ResultClickTracker) extends Logging{

  val currentTime = currentDateTime.getMillis()
  val (config, experimentId) = searchConfigManager.getConfig(userId, queryString)

  //========================== configs ==========================//
  val percentMatch = config.asFloat("percentMatch")
  val halfDecayMillis = config.asFloat("halfDecayHours") * (60.0f * 60.0f * 1000.0f) // hours to millis
  val proximityBoost = config.asFloat("proximityBoost")
  val semanticBoost = config.asFloat("semanticBoost")
  val svWeightMyBookMarks = config.asInt("svWeightMyBookMarks")
  val svWeightBrowsingHistory = config.asInt("svWeightBrowsingHistory")
  val svWeightClickHistory = config.asInt("svWeightClickHistory")
  val similarity = Similarity(config.asString("similarity"))
  val enableCoordinator = config.asBoolean("enableCoordinator")
  val phraseBoost = config.asFloat("phraseBoost")
  val phraseProximityBoost = config.asFloat("phraseProximityBoost")
  val siteBoost = config.asFloat("siteBoost")
  val maxResultClickBoost = config.asFloat("maxResultClickBoost")

  //===================== some preparations ==========================//
  val filter: Option[String] = None         // TODO: receive filter info from frontend. It's reasonable to assume most (>99%) search is done without any filter
  val context: Option[String] = None        // TODO: similar as above. Default context is empty
  val idFilter = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))

  val (friendIds, searchFilter) = {
    db.readOnly { implicit s =>
      val friendIds = socialConnectionRepo.getFortyTwoUserConnections(userId)
      val searchFilter = filter match {
        case Some("m") =>
          SearchFilter.mine(idFilter)
        case Some("f") =>
          SearchFilter.friends(idFilter)
        case Some(ids) =>
          val userIds = ids.split('.').flatMap(id => Try(ExternalId[User](id)).toOption).flatMap(userRepo.getOpt(_)).flatMap(_.id)
          SearchFilter.custom(idFilter, userIds.toSet)
        case None =>
          SearchFilter.default(idFilter)
      }
      (friendIds, searchFilter)
    }
  }

 // val searcher = mainSearcherFactory(userId, friendIds, searchFilter, config)
  val uriGraphSearcher = uriGraph.getURIGraphSearcher
  val articleSearcher = articleIndexer.getSearcher
  val myUriEdges = uriGraphSearcher.getUserToUriEdgeSetWithCreatedAt(userId, publicOnly = false)            // my keeps
  val myUris = myUriEdges.destIdLongSet
  val myPublicUris = uriGraphSearcher.getUserToUriEdgeSet(userId, publicOnly = true).destIdLongSet
  val filteredFriendIds = searchFilter.filterFriends(friendIds)
  val friendUris = filteredFriendIds.foldLeft(Set.empty[Long]) { (s, f) =>
    s ++ uriGraphSearcher.getUserToUriEdgeSet(f, publicOnly = true).destIdLongSet
  }
  val friendlyUris = {
    if (searchFilter.includeMine) myUris ++ friendUris
    else if (searchFilter.includeShared) friendUris
    else friendUris -- myUris // friends only
  }

  val customFilterOn = (filteredFriendIds != friendIds)
  val friendEdgeSet = uriGraphSearcher.getUserToUserEdgeSet(userId, friendIds)
  val filteredFriendEdgeSet = if (customFilterOn) uriGraphSearcher.getUserToUserEdgeSet(userId, filteredFriendIds) else friendEdgeSet

  val clickBoosts = resultClickTracker.getBoosts(userId, queryString, maxResultClickBoost)

  def findSharingUsers(id: Id[NormalizedURI]) = {
    val sharingUsers = uriGraphSearcher.intersect(friendEdgeSet, uriGraphSearcher.getUriToUserEdgeSet(id)).destIdSet
    val effectiveSharingSize = if (customFilterOn) searchFilter.filterFriends(sharingUsers).size else sharingUsers.size
    (sharingUsers, effectiveSharingSize)
  }

  private def getPublicBookmarkCount(id: Long) = {
    uriGraphSearcher.getUriToUserEdgeSet(Id[NormalizedURI](id)).size
  }

  def getPersonalizedSearcher(query: Query) = {
    val indexReader = uriGraphSearcher.openPersonalIndex(userId, query) match {
      case Some((personalReader, personalIdMapper)) =>
        articleSearcher.indexReader.add(personalReader, personalIdMapper)
      case None =>
        articleSearcher.indexReader
    }
    PersonalizedSearcher(userId, indexReader, myUris, browsingHistoryTracker, clickHistoryTracker, svWeightMyBookMarks, svWeightBrowsingHistory, svWeightClickHistory)
  }

  //===================== preparation done ===========================//

  val parsedQuery = {
    val lang = Lang("en")           //TODO: detect
    val parser = parserFactory(lang, proximityBoost, semanticBoost, phraseBoost, phraseProximityBoost, siteBoost)
    parser.setPercentMatch(percentMatch)
    parser.enableCoord = enableCoordinator
    parser.parse(queryString)
  }

  private def getBookmarkScore(bookmarkCount: Int) = (1.0f - (1.0f / (1.0f + bookmarkCount.toFloat)))

  private def getRecencyScore(createdAt: Long): Float = {
    val t = max(currentTime - createdAt, 0).toFloat / halfDecayMillis               // there is a lag between "currentTime" and the "actual search" time
    val t2 = t * t
    (1.0f / (1.0f + t2))
  }

  def getUriInfo() = {
    log.info("start fetching uri info ...")
    var uriInfoMap = Map.empty[Id[NormalizedURI], UriInfo]
    val idsetFilter = new IdSetFilter(targetUriIds.map{_.id}.toSet)

    parsedQuery.map { articleQuery =>
      val filteredQuery = new FilteredQuery(articleQuery, idsetFilter)
      val personalizedSearcher = getPersonalizedSearcher(filteredQuery)
      personalizedSearcher.setSimilarity(similarity)
      personalizedSearcher.doSearch(articleQuery) { (scorer, mapper) =>
        var doc = scorer.nextDoc()
        while (doc != NO_MORE_DOCS) {
          val id = mapper.getId(doc)
          val normalizedId = Id[NormalizedURI](id)

          if (!idFilter.contains(id) && targetUriIds.contains(normalizedId)) {

            val clickBoost = clickBoosts(id)
            val isBookmark = myUris.contains(id)
            val isPrivate = isBookmark && !myPublicUris.contains(id)

            val recencyScore = if (isBookmark) getRecencyScore(myUriEdges.getCreatedAt(normalizedId)) else 0.0f
            val textScore = scorer.score()

            val (sharingUsers, effectiveSharingSize) = findSharingUsers(normalizedId)
            val totalCounts = getPublicBookmarkCount(id)
            val bookmarkSize = if (!searchFilter.includeOthers) {
              if (isBookmark) effectiveSharingSize + 1 else effectiveSharingSize
            } else {
              totalCounts
            }

            val friendsKeepsCount = uriGraphSearcher.getUserToUserEdgeSet(userId, friendIds).size
            val bookmarkScore = getBookmarkScore(bookmarkSize)

            uriInfoMap += normalizedId -> UriInfo(normalizedId, textScore, bookmarkScore, recencyScore,
              clickBoost, isBookmark, isPrivate, friendsKeepsCount, totalCounts)
          }
          doc = scorer.nextDoc()
        }
      }
    }
    log.info("uri info fetched !!!")
    uriInfoMap
  }

}
