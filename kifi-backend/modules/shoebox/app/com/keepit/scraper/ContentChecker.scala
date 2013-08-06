package com.keepit.scraper

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.scraper.extractor.Extractor
import com.keepit.scraper.extractor.DefaultExtractorProvider
import scala.util.{Success, Failure}
import com.keepit.common.net.URI
import com.keepit.common.logging.Logging
import com.keepit.model.UnscrapableRepo
import com.keepit.common.db.slick._
import org.apache.http.HttpStatus
import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.concurrent.duration._

@ImplementedBy(classOf[ContentCheckerImpl])
trait ContentChecker {
  def check(urls: List[String]): Either[ContentCheckFail, ContentCheckSuccess]
}

case class ContentCheckSuccess(finalUrl: String)
case class ContentCheckFail(urls: List[String])

@Singleton
class ContentCheckerImpl @Inject()(
  db: Database,
  httpFetcher: HttpFetcher,
  unscrapableRepo: UnscrapableRepo
) extends ContentChecker with Logging {

  override def check(urls: List[String]): Either[ContentCheckFail, ContentCheckSuccess] = {
    val resultsFuture = Future.sequence(urls.map{ url => future{getHashCode(url)}})
    val rv = resultsFuture.map{ results =>
      results.forall(x => x.isRight) match {
        case false => Left[ContentCheckFail, ContentCheckSuccess](ContentCheckFail(urls))
        case true => {
          val signatures = results.flatMap{
            case Right(signature) => Some(signature)
            case Left(msg) => None
          }
          val similar = {
            if (signatures.size == 1) true
            else signatures.tail.forall( x => x.similarTo(signatures.head) > 0.9)
          }
          similar match {
            case true => Right[ContentCheckFail, ContentCheckSuccess](ContentCheckSuccess(urls.head))     // lack of total order yet.
            case false => Left[ContentCheckFail, ContentCheckSuccess](ContentCheckFail(urls))
          }
        }
      }
    }
    Await.result(rv, 5 minutes)
  }

  private def getHashCode(url: String): Either[String, Signature] = {
    val extractor = getExtractor(url)
    try {
      val fetchStatus = httpFetcher.fetch(url) { input => extractor.process(input) }

      fetchStatus.statusCode match {
        case HttpStatus.SC_OK =>
          if (isUnscrapable(url, fetchStatus.destinationUrl)) {
            Left("not_scrapable")
          } else {
            val content = extractor.getContent
            val title = getTitle(extractor)
            val description = getDescription(extractor)
            val signature = computeSignature(title, description.getOrElse(""), content)
            Right(signature)
          }
        case _ => Left("fetch_failed")
      }
    } catch {
      case e: Throwable => Left("fetch_failed")
    }
  }

  private def getExtractor(url: String): Extractor = {
    try {
      URI.parse(url) match {
        case Success(uri) =>
          Extractor.factories.find(_.isDefinedAt(uri)).map{ f =>
            f.apply(uri)
          }.getOrElse(throw new Exception("failed to find an extractor factory"))
        case Failure(_) =>
          log.warn("uri parsing failed: [%s]".format(url))
          DefaultExtractorProvider(url)
      }
    } catch {
      case e: Throwable =>
          log.warn("uri parsing failed: [%s][%s]".format(url, e.toString))
          DefaultExtractorProvider(url)
    }
  }

  protected def isUnscrapable(url: String, destinationUrl: Option[String]) = {
    db.readOnly { implicit s =>
      (unscrapableRepo.contains(url) || (destinationUrl.isDefined && unscrapableRepo.contains(destinationUrl.get)))
    }
  }

  private[this] def getTitle(x: Extractor): String = {
    x.getMetadata("title").getOrElse("")
  }
  private[this] def getDescription(x: Extractor): Option[String] = {
    x.getMetadata("description").orElse(x.getMetadata("Description")).orElse(x.getMetadata("DESCRIPTION"))
  }

  private[this] def computeSignature(fields: String*) = fields.foldLeft(new SignatureBuilder){ (builder, text) => builder.add(text) }.build
}