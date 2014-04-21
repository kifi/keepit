package com.keepit.cortex

import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.routes.Cortex
import scala.concurrent.Future
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI



trait CortexServiceClient extends ServiceClient{
  final val serviceType = ServiceType.CORTEX

  def word2vecWordSimilarity(word1: String, word2: String): Future[Option[Float]]
  def word2vecKeywordsAndBOW(text: String): Future[Map[String, String]]
  def word2vecURISimilairty(uri1: Id[NormalizedURI], uri2: Id[NormalizedURI]): Future[Option[Float]]
}

class CortexServiceClientImpl(
  override val serviceCluster: ServiceCluster,
  override val httpClient: HttpClient,
  val airbrakeNotifier: AirbrakeNotifier
) extends CortexServiceClient {

  def word2vecWordSimilarity(word1: String, word2: String): Future[Option[Float]] = {
    call(Cortex.internal.word2vecSimilairty(word1, word2)).map{ r =>
      Json.fromJson[Option[Float]](r.json).get
    }
  }

  def word2vecKeywordsAndBOW(text: String): Future[Map[String, String]] = {
    val payload = Json.obj("query" -> text)
    call(Cortex.internal.keywordsAndBow(), payload).map{ r =>
      Json.fromJson[Map[String, String]](r.json).get
    }
  }

  def word2vecURISimilairty(uri1: Id[NormalizedURI], uri2: Id[NormalizedURI]): Future[Option[Float]] = {
    call(Cortex.internal.word2vecURISimilarity(uri1, uri2)).map{ r =>
      Json.fromJson[Option[Float]](r.json).get
    }
  }



}
