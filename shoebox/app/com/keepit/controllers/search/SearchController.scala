
package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.search.ResultClickTracker.ResultClickBoosts
import com.keepit.search._
import com.keepit.search.index.{MutableHit, HitQueue}
import org.apache.commons.math3.linear.{EigenDecomposition, Array2DRowRealMatrix}
import play.api.mvc.Action
import scala.collection.mutable.ArrayBuffer
import scala.math.{abs, sqrt}
import views.html
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.model.User
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random
import play.api.libs.json._

class SearchController @Inject()(
    searchConfigManager: SearchConfigManager,
    searcherFactory: MainSearcherFactory,
    shoeboxClient: ShoeboxServiceClient
  ) extends SearchServiceController {

  def searchKeeps(userId: Id[User], query: String) = Action { request =>
    val searcher = searcherFactory.bookmarkSearcher(userId)
    val uris = searcher.search(query, Lang("en"))
    Ok(JsArray(uris.toSeq.map(JsNumber(_))))
  }

  def explain(query: String, userId: Id[User], uriId: Id[NormalizedURI]) = Action { request =>
    val friendIdsFuture = shoeboxClient.getConnectedUsers(userId)
    val friendIds = Await.result(friendIdsFuture, 5 seconds)
    val (config, _) = searchConfigManager.getConfig(userId, query)

    val searcher = searcherFactory(userId, friendIds, SearchFilter.default(), config)
    val explanation = searcher.explain(query, uriId)
    Ok(html.admin.explainResult(query, userId, uriId, explanation))
  }

  def friendMapJson(userId: Id[User], q: Option[String] = None, minKeeps: Option[Int]) = Action { implicit request =>
    val data = new ArrayBuffer[JsArray]
    q.foreach{ q =>
      val friendIdsFuture = shoeboxClient.getConnectedUsers(userId)
      val friendIds = Await.result(friendIdsFuture, 5 seconds)
      val allUserIds = (friendIds + userId).toArray

      val searcher = searcherFactory.semanticVectorSearcher()
      val vectorMap = searcher.getSemanticVectors(allUserIds, q, Lang("en"), minKeeps.getOrElse(1))
      val size = vectorMap.size
      val userIndex = vectorMap.keys.toArray
      val vectors = userIndex.map{ u => vectorMap(u) }

      // use the Quantification Method IV to map friends onto 2D space
      if (size > 2) {
        val matrix = {
          val m = Array.tabulate(size, size){ (i, j) => if (i == j) 0.0d else similarity(vectors(i), vectors(j)) }
          (0 until size).foreach{ i => m(i)(i) = (0 until size).foldLeft(0.0d){ (sum, j) => sum - m(i)(j) } }
          new Array2DRowRealMatrix(m)
        }
        val decomposition = new EigenDecomposition(matrix)

        try {
          val eigenVals = decomposition.getRealEigenvalues()
          val minEigenVal = eigenVals(size - 2)

          def getValues(n: Int) = {
            val eigenVal = eigenVals(n)
            val norm = sqrt(eigenVal - minEigenVal)
            val vec = decomposition.getEigenvector(n).toArray
            vec.map(v => v * norm)
          }

          val x = getValues(0)
          val y = getValues(1)

          val norm = {
            val n = Seq(abs(x.max), abs(x.min), abs(y.max), abs(y.min)).max * 1.1
            if (n == 0 || n.isNaN()) 1.0 else n
          }

          val positionMap = (0 until size).foldLeft(Map.empty[Id[User],(Double,Double)]){ (m,i) =>
            m + (userIndex(i) -> (x(i)/norm, y(i)/norm))
          }

          val usersFuture = shoeboxClient.getUsers(userIndex)
          Await.result(usersFuture, 5 seconds).foreach { user =>
              val (px,py) = positionMap(user.id.get)
              data += JsArray(Seq(JsNumber(px), JsNumber(py), JsString("%s %s".format(user.firstName, user.lastName))))
          }
        } catch {
          case e: ArrayIndexOutOfBoundsException => // ignore. not enough eigenvectors
          case e: Exception => log.error("friend mapping failed", e)
        }
      }
    }
    Ok(JsArray(data))
  }

  private def similarity(vectors1: Array[SemanticVector], vectors2: Array[SemanticVector]) = {
    val s = vectors1.zip(vectors2).foldLeft(0.0d){ case (sum, (v1, v2)) =>
      if (v1.bytes.isEmpty || v2.bytes.isEmpty) sum
      else {
        val tmp = v1.similarity(v2).toDouble
        sum + tmp*tmp
      }
    }
    (sqrt(s) / vectors1.length) - 1.0d - Double.MinPositiveValue
  }

  //randomly creates one of two exceptions, each time with a random exception message
  def causeError() = Action { implicit request =>
    if (Random.nextBoolean) {
      // throwing a X/0 exception. its a fixed stack exception with random message text
      (Random.nextInt) / 0
    }
    // throwing an array out bound exception. its a fixed stack exception with random message text
    (new Array[Int](1))(Random.nextInt + 1) = 1
    Ok("You cannot see this :-P ")
  }


}
