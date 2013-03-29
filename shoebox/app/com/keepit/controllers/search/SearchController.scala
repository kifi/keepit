
package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.search.ResultClickTracker.ResultClickBoosts
import com.keepit.search._
import com.keepit.search.index.{MutableHit, HitQueue}
import org.apache.commons.math3.linear.{EigenDecomposition, Array2DRowRealMatrix}
import play.api.libs.json.JsArray
import play.api.libs.json.JsNumber
import play.api.libs.json.JsString
import play.api.mvc.Action
import scala.collection.mutable.ArrayBuffer
import scala.math.{abs, sqrt}
import views.html

class SearchController @Inject()(
    db: Database,
    socialConnectionRepo: SocialConnectionRepo,
    searchConfigManager: SearchConfigManager,
    userRepo: UserRepo,
    bookmarkRepo: BookmarkRepo,
    normUriRepo: NormalizedURIRepo,
    searcherFactory: MainSearcherFactory
  ) extends FortyTwoController {

  def searchKeeps(userId: Id[User], query: String) = Action { request =>
    val searcher = searcherFactory.bookmarkSearcher(userId)
    val uris = searcher.search(query, Lang("en"))
    Ok(JsArray(uris.toSeq.map(JsNumber(_))))
  }

  def explain(query: String, userId: Id[User], uriId: Id[NormalizedURI]) = Action { request =>
    val friendIds = db.readOnly { implicit s =>
      socialConnectionRepo.getFortyTwoUserConnections(userId)
    }
    val (config, _) = searchConfigManager.getConfig(userId, query)

    val searcher = searcherFactory(userId, friendIds, SearchFilter.default(), config)
    val explanation = searcher.explain(query, uriId)

    Ok(html.admin.explainResult(query, userId, uriId, explanation))
  }

  def rankVsScoreJson(q: Option[String] = None) = Action {
    val topN = 50
    val fakeUserId = Id[User](-1)
    val config = searchConfigManager.defaultConfig
    val searcher = searcherFactory(fakeUserId, Set.empty[Id[User]], SearchFilter.default(), config)
    val hits = new HitQueue(topN)
    val nullClickBoost = new ResultClickBoosts{ def apply(value: Long) = 1.0f }
    q.foreach{ query =>
      val (myHits, friendsHits, othersHits, parsedQuery) = searcher.searchText(query, 20, nullClickBoost)(Lang("en"))
      myHits.foreach{ h => hits.insertWithOverflow(new MutableHit(h.id, h.score))}
      friendsHits.foreach{ h => hits.insertWithOverflow(new MutableHit(h.id, h.score))}
      othersHits.foreach{ h => hits.insertWithOverflow(new MutableHit(h.id, h.score))}
    }
    val data = db.readOnly { implicit s =>
      var data = List.empty[JsArray]
      while (hits.size > 0) {
        val top = hits.top
        val uri = normUriRepo.get(Id[NormalizedURI](top.id))
        var title = uri.title.map(_.trim).getOrElse("")
        if (title == "") title = uri.url
        data = JsArray(Seq(JsNumber(hits.size), JsNumber(top.score), JsString(title)))::data
        hits.pop
      }
      data
    }
    Ok(JsArray(data))
  }

  def friendMapJson(userId: Id[User], q: Option[String] = None, minKeeps: Option[Int]) = Action { implicit request =>
    val data = new ArrayBuffer[JsArray]
    q.foreach{ q =>
      val friendIds = db.readOnly { implicit s =>
        socialConnectionRepo.getFortyTwoUserConnections(userId)
      }
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

          db.readOnly { implicit s =>
            (0 until size).map{ i =>
              val user = userRepo.get(userIndex(i))
              data += JsArray(Seq(JsNumber(x(i)/norm), JsNumber(y(i)/norm), JsString("%s %s".format(user.firstName, user.lastName))))
            }
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
}
