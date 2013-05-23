package com.keepit.shoebox

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise
import scala.concurrent.{Future, promise}
import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.ElectronicMail
import com.keepit.search.SearchConfigExperiment
import com.keepit.common.db.State
import com.keepit.common.net.HttpClient
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.time._
import com.keepit.controllers.shoebox._
import com.keepit.model._
import com.keepit.serializer._
import play.api.libs.json._
import com.keepit.search.ActiveExperimentsCache
import com.keepit.search.ActiveExperimentsKey

trait ShoeboxServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SHOEBOX

  def getUsers(userIds: Seq[Id[User]]): Future[Seq[User]]
  def getConnectedUsers(userId: Id[User]): Future[Set[Id[User]]]
  def getNormalizedURI(uriId: Id[NormalizedURI]) : Future[NormalizedURI]
  def getNormalizedURIs(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[NormalizedURI]]
  def sendMail(email: ElectronicMail): Future[Boolean]
  def getUsersChanged(seqNum: SequenceNumber): Future[Seq[(Id[User], SequenceNumber)]]
  def persistServerSearchEvent(metaData: JsObject): Unit
  def getClickHistoryFilter(userId: Id[User]): Future[Array[Byte]]
  def getBrowsingHistoryFilter(userId: Id[User]): Future[Array[Byte]]
  def getPhrasesByPage(page: Int, size: Int): Future[Seq[Phrase]]
  def getBookmarksInCollection(id: Id[Collection]): Future[Seq[Bookmark]]
  def getCollectionsChanged(seqNum: SequenceNumber): Future[Seq[(Id[Collection], Id[User], SequenceNumber)]]
  def getCollectionsByUser(userId: Id[User]): Future[Seq[Id[Collection]]]
  def getIndexable(seqNum: Long, fetchSize: Int): Future[Seq[NormalizedURI]]
  def getBookmarks(userId: Id[User]): Future[Seq[Bookmark]]
  def getActiveExperiments: Future[Seq[SearchConfigExperiment]]
  def getExperiments: Future[Seq[SearchConfigExperiment]]
  def getExperiment(id: Id[SearchConfigExperiment]): Future[SearchConfigExperiment]
  def saveExperiment(experiment: SearchConfigExperiment): Future[SearchConfigExperiment]
  def hasExperiment(userId: Id[User], state: State[ExperimentType]): Future[Boolean]
}

case class ShoeboxCacheProvider @Inject() (
    uriIdCache: NormalizedURICache,
    clickHistoryCache: ClickHistoryUserIdCache,
    browsingHistoryCache: BrowsingHistoryUserIdCache,
    activeSearchConfigExperimentsCache: ActiveExperimentsCache,
    userExperimentCache: UserExperimentCache)

class ShoeboxServiceClientImpl @Inject() (
  override val host: String,
  override val port: Int,
  override val httpClient: HttpClient,
  cacheProvider: ShoeboxCacheProvider)
    extends ShoeboxServiceClient {


  def getBookmarks(userId: Id[User]): Future[Seq[Bookmark]] = {
    call(routes.ShoeboxController.getBookmarks(userId)).map{ r =>
      r.json.as[JsArray].value.map(js => BookmarkSerializer.fullBookmarkSerializer.reads(js).get)
    }
  }

  def sendMail(email: ElectronicMail): Future[Boolean] = {
    call(routes.ShoeboxController.sendMail(), Json.toJson(email)).map(r => r.body.toBoolean)
  }

  def getUsers(userIds: Seq[Id[User]]): Future[Seq[User]] = {
    val query = userIds.mkString(",")
    call(routes.ShoeboxController.getUsers(query)).map {r =>
      r.json.as[JsArray].value.map(js => UserSerializer.userSerializer.reads(js).get)
    }
  }

  def getConnectedUsers(userId: Id[User]): Future[Set[Id[User]]] = {
    call(routes.ShoeboxController.getConnectedUsers(userId)).map {r =>
      r.json.as[JsArray].value.map(jsv => Id[User](jsv.as[Long])).toSet
    }
  }

  def getNormalizedURI(uriId: Id[NormalizedURI]) : Future[NormalizedURI] = {
    cacheProvider.uriIdCache.get(NormalizedURIKey(Id[NormalizedURI](uriId.id))) match {
      case Some(uri) =>  promise[NormalizedURI]().success(uri).future
      case None => call(routes.ShoeboxController.getNormalizedURI(uriId.id)).map(r => NormalizedURISerializer.normalizedURISerializer.reads(r.json).get)
    }
  }

  def getNormalizedURIs(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[NormalizedURI]] = {
    val query = uriIds.mkString(",")
    call(routes.ShoeboxController.getNormalizedURIs(query)).map { r =>
      r.json.as[JsArray].value.map(js => NormalizedURISerializer.normalizedURISerializer.reads(js).get)
    }
  }

  def getUsersChanged(seqNum: SequenceNumber): Future[Seq[(Id[User], SequenceNumber)]] = {
    call(routes.ShoeboxController.getUsersChanged(seqNum.value)).map{ r =>
      r.json.as[JsArray].value.map{ json =>
        val id = (json \ "id").as[Long]
        val seqNum = (json \ "seqNum").as[Long]
        (Id[User](id), SequenceNumber(seqNum))
      }
    }
  }

  def getClickHistoryFilter(userId: Id[User]): Future[Array[Byte]] = {
    cacheProvider.clickHistoryCache.get(ClickHistoryUserIdKey(userId)) match {
      case Some(clickHistory) => Promise.successful(clickHistory.filter).future
      case None => call(routes.ShoeboxController.getClickHistoryFilter(userId)).map(_.body.getBytes)
    }
  }

  def getBrowsingHistoryFilter(userId: Id[User]): Future[Array[Byte]] = {
    cacheProvider.browsingHistoryCache.get(BrowsingHistoryUserIdKey(userId)) match {
      case Some(browsingHistory) => Promise.successful(browsingHistory.filter).future
      case None => call(routes.ShoeboxController.getBrowsingHistoryFilter(userId)).map(_.body.getBytes)
    }
  }


  def persistServerSearchEvent(metaData: JsObject): Unit ={
     call(routes.ShoeboxController.persistServerSearchEvent, metaData)
  }

  def getPhrasesByPage(page: Int, size: Int): Future[Seq[Phrase]] = {
    call(routes.ShoeboxController.getPhrasesByPage(page, size)).map { r =>
      r.json.as[JsArray].value.map(jsv => PhraseSerializer.phraseSerializer.reads(jsv).get)
    }
  }

  def getCollectionsChanged(seqNum: SequenceNumber): Future[Seq[(Id[Collection], Id[User], SequenceNumber)]] = {
    import com.keepit.controllers.shoebox.ShoeboxController.collectionTupleFormat
    call(routes.ShoeboxController.getCollectionsChanged(seqNum.value)) map { r =>
      Json.fromJson[Seq[(Id[Collection], Id[User], SequenceNumber)]](r.json).get
    }
  }

  def getBookmarksInCollection(collectionId: Id[Collection]): Future[Seq[Bookmark]] = {
    call(routes.ShoeboxController.getBookmarksInCollection(collectionId)) map { r =>
      Json.fromJson[Seq[Bookmark]](r.json).get
    }
  }

  def getActiveExperiments: Future[Seq[SearchConfigExperiment]] = {
    cacheProvider.activeSearchConfigExperimentsCache.get(ActiveExperimentsKey) match {
      case Some(exps) => Promise.successful(exps).future
      case None => call(routes.ShoeboxController.getActiveExperiments).map { r =>
        r.json.as[JsArray].value.map { SearchConfigExperimentSerializer.serializer.reads(_).get }
      }
    }
  }
  def getExperiments: Future[Seq[SearchConfigExperiment]] = {
    call(routes.ShoeboxController.getExperiments).map{r =>
      r.json.as[JsArray].value.map{SearchConfigExperimentSerializer.serializer.reads(_).get}
    }
  }
  def getExperiment(id: Id[SearchConfigExperiment]): Future[SearchConfigExperiment] = {
    call(routes.ShoeboxController.getExperiment(id)).map{ r =>
      SearchConfigExperimentSerializer.serializer.reads(r.json).get
    }
  }
  def saveExperiment(experiment: SearchConfigExperiment): Future[SearchConfigExperiment] = {
    call(routes.ShoeboxController.saveExperiment, SearchConfigExperimentSerializer.serializer.writes(experiment)).map{ r =>
      SearchConfigExperimentSerializer.serializer.reads(r.json).get
    }
  }
  def hasExperiment(userId: Id[User], state: State[ExperimentType]): Future[Boolean] = {
    cacheProvider.userExperimentCache.get(UserExperimentUserIdKey(userId)) match {
      case Some(expTypes) => Promise.successful(expTypes.contains(state)).future
      case None => call(routes.ShoeboxController.hasExperiment(userId, state)).map { r =>
        r.json.as[Boolean]
      }
    }

  }

  def getCollectionsByUser(userId: Id[User]): Future[Seq[Id[Collection]]] = {
    call(routes.ShoeboxController.getCollectionsByUser(userId)).map { r =>
      Json.fromJson[Seq[Long]](r.json).get.map(Id[Collection](_))
    }
  }


   def getIndexable(seqNum: Long, fetchSize: Int): Future[Seq[NormalizedURI]] = {
     call(routes.ShoeboxController.getIndexable(seqNum, fetchSize)).map{
       r => r.json.as[JsArray].value.map(js => NormalizedURISerializer.normalizedURISerializer.reads(js).get)
     }
   }

}
