package com.keepit.controllers

import play.api.data._
import play.api._
import play.api.data.Forms._
import play.api.libs.ws.WS
import play.api.mvc._
import play.api.http.ContentTypes
import play.api.Play.current
import play.api.libs.json.JsArray
import play.api.libs.json.Json
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.json.JsNumber
import com.keepit.controllers.CommonActions._
import com.keepit.inject._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.controller.FortyTwoController
import com.keepit.model._
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.index.Hit
import com.keepit.search.graph._
import com.keepit.search._
import com.keepit.search.ArticleSearchResultStore
import com.keepit.search.index.HitQueue
import com.keepit.search.index.MutableHit
import com.keepit.search.ResultClickTracker.ResultClickBoosts
import scala.collection.mutable.ArrayBuffer
import scala.math._
import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.EigenDecomposition
import java.util.Random

object SearchLabsController extends FortyTwoController {

  def rankVsScore(q: Option[String] = None) = AdminHtmlAction { implicit request =>
    Ok(views.html.labs.rankVsScore(q))
  }
  def rankVsScoreJson(q: Option[String] = None) = AdminJsonAction { implicit request =>
    val topN = 50
    val fakeUserId = Id[User](-1)
    val mainSearcherFactory = inject[MainSearcherFactory]
    val config = inject[SearchConfigManager].getDefaultConfig
    val searcher = mainSearcherFactory(fakeUserId, Set.empty[Id[User]], Set.empty[Long], config)
    val hits = new HitQueue(topN)
    val nullClickBoost = new ResultClickBoosts{ def apply(value: Long) = 1.0f }
    q.foreach{ query =>
      val (myHits, friendsHits, othersHits) = searcher.searchText(query, 20, nullClickBoost)(Lang("en"))
      myHits.foreach{ h => hits.insertWithOverflow(new MutableHit(h.id, h.score))}
      friendsHits.foreach{ h => hits.insertWithOverflow(new MutableHit(h.id, h.score))}
      othersHits.foreach{ h => hits.insertWithOverflow(new MutableHit(h.id, h.score))}
    }
    var data = inject[DBConnection].readOnly { implicit s =>
      var data = List.empty[JsArray]
      while (hits.size > 0) {
        val top = hits.top
        val uri = inject[NormalizedURIRepo].get(Id[NormalizedURI](top.id))
        var title = uri.title.map(_.trim).getOrElse("")
        if (title == "") title = uri.url
        data = JsArray(Seq(JsNumber(hits.size), JsNumber(top.score), JsString(title)))::data
        hits.pop
      }
      data
    }

    Ok(JsObject(Seq("data" -> JsArray(data))))
  }

  def friendMap(q: Option[String] = None) = AdminHtmlAction { implicit request =>
    Ok(views.html.labs.friendMap(q))
  }
  def friendMapJson(q: Option[String] = None) = AdminJsonAction { implicit request =>
    val data = new ArrayBuffer[JsArray]
    q.foreach{ q =>
      val userId = request.userId
      val friendIds = inject[DBConnection].readOnly { implicit s =>
        inject[SocialConnectionRepo].getFortyTwoUserConnections(userId)
      }
      val allUserIds = (friendIds + userId).toArray

      val mainSearcherFactory = inject[MainSearcherFactory]
      val searcher = mainSearcherFactory.semanticVectorSearcher()
      val vectorMap = searcher.getSemanticVectors(allUserIds, q, Lang("en"))
      val size = vectorMap.size
      val userIndex = vectorMap.keys.toArray
      val vectors = userIndex.map{ u => vectorMap(u) }

      // use the Quantification Method IV to map friends onto 2D space
      if (size > 2) {
        val matrix = {
          val m = Array.tabulate(size, size){ (i, j) =>
            if (i == j) 0.0d else SemanticVector.similarity(vectors(i), vectors(j)).toDouble
          }
          (0 until size).foreach{ i => m(i)(i) = (0 until size).foldLeft(0.0d){ (sum, j) => sum + m(i)(j) } }
          new Array2DRowRealMatrix(m)
        }
        val decomposition = new EigenDecomposition(matrix)
        try {
          val x = decomposition.getEigenvector(0).toArray()
          val y = decomposition.getEigenvector(1).toArray()
          val norm = {
            val n = Seq(abs(x.max), abs(x.min), abs(y.max), abs(y.min)).max * 1.1
            if (n == 0 || n.isNaN()) 1.0 else n
          }

          inject[DBConnection].readOnly { implicit s =>
            val userRepo = inject[UserRepo]
            (0 until size).map{ i =>
              val user = userRepo.get(userIndex(i))
              data += JsArray(Seq(JsNumber(x(i)/norm), JsNumber(y(i)/norm), JsString("%s %s".format(user.firstName, user.lastName))))
            }
          }
        } catch {
          case e: ArrayIndexOutOfBoundsException => // ignore. not enough eigenvectors
        }
      }
    }
    Ok(JsObject(Seq("data" -> JsArray(data))))
  }
}
