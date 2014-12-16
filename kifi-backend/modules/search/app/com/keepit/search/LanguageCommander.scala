package com.keepit.search

import com.keepit.common.service.RequestConsolidator
import com.keepit.search.index.sharding.Shard
import com.keepit.model.{ User, NormalizedURI }
import com.keepit.common.db.Id
import com.keepit.common.zookeeper.ServiceInstance
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.Future
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.common.akka.SafeFuture
import com.keepit.search.index.graph.keep.{ ShardedKeepIndexer, KeepLangs }
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.search.engine.SearchFactory
import com.keepit.common.logging.Logging
import play.api.libs.concurrent.Execution.Implicits.defaultContext

@ImplementedBy(classOf[LanguageCommanderImpl])
trait LanguageCommander {
  def getLangs(
    localShards: Set[Shard[NormalizedURI]],
    dispatchPlan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])],
    userId: Id[User],
    query: String,
    acceptLangCodes: Seq[String],
    libraryContext: LibraryContext): Future[(Lang, Option[Lang])]

  def distLangFreqs(shards: Set[Shard[NormalizedURI]], userId: Id[User], libraryContext: LibraryContext): Future[Map[Lang, Int]]
}

@Singleton
class LanguageCommanderImpl @Inject() (
    searchClient: DistributedSearchServiceClient,
    searchFactory: SearchFactory,
    shardedKeepIndexer: ShardedKeepIndexer) extends LanguageCommander with Logging {

  private[this] val reqConsolidator = new RequestConsolidator[(Id[User], Long), ListBuffer[Map[Lang, Int]]](60 seconds)
  private[this] val localConsolidator = new RequestConsolidator[(Id[User], Long), Map[Lang, Int]](60 seconds)

  private def getLibraryId(libraryContext: LibraryContext) = {
    libraryContext match {
      case LibraryContext.Authorized(libId) => libId
      case LibraryContext.NotAuthorized(libId) => libId
      case _ => -1L
    }
  }

  def getLangs(
    localShards: Set[Shard[NormalizedURI]],
    dispatchPlan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])],
    userId: Id[User],
    query: String,
    acceptLangCodes: Seq[String],
    libraryContext: LibraryContext): Future[(Lang, Option[Lang])] = {

    def getLangsPriorProbabilities(majorLangs: Set[Lang], majorLangProb: Double): Map[Lang, Double] = {
      val numberOfLangs = majorLangs.size
      val eachLangProb = (majorLangProb / numberOfLangs)
      majorLangs.map(_ -> eachLangProb).toMap
    }

    // TODO: use user profile info as a bias

    val libId = getLibraryId(libraryContext)

    val future = reqConsolidator((userId, libId)) { key =>
      val resultFutures = new ListBuffer[Future[Map[Lang, Int]]]()

      if (dispatchPlan.nonEmpty) {
        resultFutures ++= searchClient.distLangFreqs(dispatchPlan, userId, libraryContext)
      }
      if (localShards.nonEmpty) {
        resultFutures += distLangFreqs(localShards, userId, libraryContext)
      }

      Future.sequence(resultFutures)
    }

    val acceptLangs = parseAcceptLangs(acceptLangCodes)

    future.map { freqs =>
      val langProf = {
        val total = freqs.map(_.values.sum).sum.toFloat
        freqs.map(_.iterator).flatten.foldLeft(Map[Lang, Float]()) {
          case (m, (lang, count)) =>
            m + (lang -> (count.toFloat / total + m.getOrElse(lang, 0.0f)))
        }.filter { case (_, prob) => prob > 0.05f }.toSeq.sortBy(p => -p._2).take(3).toMap // top N with prob > 0.05
      }

      val profLangs = langProf.keySet

      var strongCandidates = acceptLangs ++ profLangs

      val firstLang = LangDetector.detectShortText(query, getLangsPriorProbabilities(strongCandidates, 0.6d))
      strongCandidates -= firstLang
      val secondLangOpt = if (strongCandidates.nonEmpty) {
        Some(LangDetector.detectShortText(query, getLangsPriorProbabilities(strongCandidates, 1.0d)))
      } else {
        None
      }

      // we may switch first/second langs
      secondLangOpt match {
        case Some(secondLang) =>
          if (firstLang == secondLang) {
            (firstLang, None)
          } else if (acceptLangs.contains(firstLang)) {
            (firstLang, secondLangOpt)
          } else if (acceptLangs.contains(secondLang)) {
            (secondLang, Some(firstLang))
          } else if (profLangs.contains(firstLang)) {
            (firstLang, secondLangOpt)
          } else if (profLangs.contains(secondLang)) {
            (secondLang, Some(firstLang))
          } else {
            (firstLang, secondLangOpt)
          }
        case None =>
          (firstLang, None)
      }
    }
  }

  private def parseAcceptLangs(acceptLangCodes: Seq[String]): Set[Lang] = {
    val langs = acceptLangCodes.toSet.flatMap { code: String =>
      val langCode = code.substring(0, 2)
      if (langCode == "zh") Set(Lang("zh-cn"), Lang("zh-tw"))
      else {
        val lang = Lang(langCode)
        if (LangDetector.languages.contains(lang)) Set(lang) else Set.empty[Lang]
      }
    }
    if (langs.isEmpty) {
      log.warn(s"defaulting to English for acceptLang=$acceptLangCodes")
      Set(DefaultAnalyzer.defaultLang)
    } else {
      langs
    }
  }

  def distLangFreqs(shards: Set[Shard[NormalizedURI]], userId: Id[User], libraryContext: LibraryContext): Future[Map[Lang, Int]] = {
    val libId = getLibraryId(libraryContext)

    localConsolidator((userId, libId)) { key =>
      searchFactory.getLibraryIdsFuture(userId, libraryContext).flatMap {
        case (_, memberLibIds, trustedPublishedLibIds, authorizedLibIds) =>
          Future.traverse(shards) { shard =>
            SafeFuture {
              val keepSearcher = shardedKeepIndexer.getIndexer(shard).getSearcher
              val keepLangs = new KeepLangs(keepSearcher)
              keepLangs.processLibraries(memberLibIds) // member libraries includes own libraries
              keepLangs.processLibraries(trustedPublishedLibIds)
              keepLangs.processLibraries(authorizedLibIds)
              keepLangs.getFrequentLangs()
            }
          }.map { results =>
            results.map(_.iterator).flatten.foldLeft(Map[Lang, Int]()) {
              case (m, (langName, count)) =>
                val lang = Lang(langName)
                m + (lang -> (count + m.getOrElse(lang, 0)))
            }
          }
      }
    }
  }
}
