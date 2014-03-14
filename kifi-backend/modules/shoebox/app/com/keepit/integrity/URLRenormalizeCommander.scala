package com.keepit.integrity

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.common.mail._
import com.google.inject.Inject
import com.keepit.normalizer._
import com.keepit.common.healthcheck.{SystemAdminMailSender, BabysitterTimeout, AirbrakeNotifier}
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.zookeeper.CentralConfig
import scala.collection.mutable
import com.keepit.common.logging.Logging

class URLRenormalizeCommander @Inject()(
  db: Database,
  systemAdminMailSender: SystemAdminMailSender,
  uriRepo: NormalizedURIRepo,
  urlRepo: URLRepo,
  changedUriRepo: ChangedURIRepo,
  renormRepo: RenormalizedURLRepo,
  centralConfig: CentralConfig
) extends Logging{

  def doRenormalize(readOnly: Boolean = true, clearSeq: Boolean = false, domainRegex: Option[String] = None) = {

    def getUrlList() = {
      val urls = db.readOnly { implicit s =>
        domainRegex match {
          case Some(regex) => urlRepo.getByDomainRegex(regex)
          case None => urlRepo.all
        }
      }.sortBy(_.id.get.id)

      val lastId = if (domainRegex.isDefined) 0L else { centralConfig(RenormalizationCheckKey) getOrElse 0L }
      urls.filter(_.id.get.id > lastId).filter(_.state == URLStates.ACTIVE)
    }

    def needRenormalization(url: URL)(implicit session: RWSession): (Boolean, Option[NormalizedURI]) = {
      uriRepo.getByUri(url.url) match {
        case None => if (!readOnly) (true, Some(uriRepo.internByUri(url.url))) else (true, None)
        case Some(uri) if (url.normalizedUriId != uri.id.get) => (true, Some(uri))
        case _ => (false, None)
      }
    }

    def batch[T](obs: Seq[T], batchSize: Int): Seq[Seq[T]] = {
      val total = obs.size
      val index = ((0 to total/batchSize).map(_*batchSize) :+ total).distinct
      (0 to (index.size - 2)).map{ i => obs.slice(index(i), index(i+1)) }
    }

    def sendStartEmail(urls: Seq[URL]) = {
      val title = "Renormalization Begins"
      val msg = s"domainRegex = ${domainRegex}, scanning ${urls.size} urls. readOnly = ${readOnly}"
      systemAdminMailSender.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = List(EmailAddresses.ENG),
        subject = title, htmlBody = msg, category = NotificationCategory.System.ADMIN))
    }

    def sendEmail(changes: Vector[(URL, Option[NormalizedURI])], readOnly: Boolean)(implicit session: RWSession) = {
      val batchChanges = batch[(URL, Option[NormalizedURI])](changes, batchSize = 1000)
      batchChanges.zipWithIndex.map{ case (batch, i) =>
        val title = "Renormalization Report: " + s"part ${i+1} of ${batchChanges.size}. ReadOnly Mode = $readOnly. Num of affected URL: ${changes.size}"
        val msg = batch.map( x => x._1.url + s"\ncurrent uri: ${ uriRepo.get(x._1.normalizedUriId).url }" + "\n--->\n" + x._2.map{_.url}).mkString("\n\n")
        systemAdminMailSender.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = List(EmailAddresses.ENG),
          subject = title, htmlBody = msg.replaceAll("\n","\n<br>"), category = NotificationCategory.System.ADMIN))
      }
    }

    case class SplittedURIs(value: Map[Id[NormalizedURI], Set[Id[URL]]])

    def sendSplitEmail(splits: Map[Id[NormalizedURI], SplittedURIs], readOnly: Boolean)(implicit session: RWSession) = {
      if (splits.size == 0){
        systemAdminMailSender.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = List(EmailAddresses.ENG),
          subject = "Renormalization Split Cases Report: no split cases found", htmlBody = "", category = NotificationCategory.System.ADMIN))
      }

      val batches = splits.toSeq.grouped(500).toArray
      batches.zipWithIndex.map{ case (batch, i) =>
        val title = "Renormalization Split Cases Report: " + s"part ${i+1} of ${batches.size}. ReadOnly Mode = $readOnly. Num of splits: ${splits.size}"
        val msg = batch.map{ case (oldUri, splitted) =>
          val line1 = s"oldUriId: ${oldUri} uri:${uriRepo.get(oldUri).url}\tsplitted into ${splitted.value.size} parts\n"
          val lines = splitted.value.map{ case (newUri, urls) =>
            val urlsInfo = urls.map{id => urlRepo.get(id)}.map{url => s"urlId: ${url.id.get}, url: ${url.url}"}.mkString("\n\n") + "\n"
            s"<---\nuriId: ${newUri}, uri: ${uriRepo.get(newUri).url} \nis referenced by following urls:\n\n" + urlsInfo + "--->\n"
          }.mkString("\n")
          "<<<----- Start of Split \n" + line1 + lines + "\nEnd of Split------>>>"
        }.mkString("\n\n===========================\n\n")
        //println(msg)
        systemAdminMailSender.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = List(EmailAddresses.ENG),
          subject = title, htmlBody = msg.replaceAll("\n","\n<br>"), category = NotificationCategory.System.ADMIN))
      }
    }


    // main code
    if (clearSeq) centralConfig.update(RenormalizationCheckKey, 0L)
    var changes = Vector.empty[(URL, Option[NormalizedURI])]
    val urls = getUrlList()
    sendStartEmail(urls)
    val batchUrls = batch[URL](urls, batchSize = 50)     // avoid long DB write lock.

    val originalRef = mutable.Map.empty[Id[NormalizedURI], Set[Id[URL]]]      // all active urls pointing to a uri initially
    db.readOnly{ implicit s =>
      urls.map{_.normalizedUriId}.toSet.foreach{ uriId: Id[NormalizedURI] =>
        val urls = urlRepo.getByNormUri(uriId).filter(_.state == URLStates.ACTIVE)
        originalRef += uriId -> urls.map{_.id.get}.toSet
      }
    }
    val migratedRef = mutable.Map.empty[Id[NormalizedURI], Set[Id[URL]]]      // urls pointing to new uri due to migration
    val potentialUriMigrations = mutable.Map.empty[Id[NormalizedURI], Set[Id[NormalizedURI]]]       // oldUri -> set of new uris. set size > 1 is a sufficient (but not necessary) condition for a "uri split"

    batchUrls.map { urls =>
      log.info(s"begin a new batch of renormalization. lastId from zookeeper: ${centralConfig(RenormalizationCheckKey)}")
      db.readWrite { implicit s =>
        urls.foreach { url =>
          needRenormalization(url) match {
            case (true, newUriOpt) => {
              changes = changes :+ (url, newUriOpt)
              newUriOpt.foreach{ uri =>
                potentialUriMigrations += url.normalizedUriId -> (potentialUriMigrations.getOrElse(url.normalizedUriId, Set()) + uri.id.get)
                migratedRef += uri.id.get -> (migratedRef.getOrElse(uri.id.get, Set()) + url.id.get)
              }
              if (!readOnly) newUriOpt.map { uri => renormRepo.save(RenormalizedURL(urlId = url.id.get, oldUriId = url.normalizedUriId, newUriId = uri.id.get))}
            }
            case _ =>
          }
        }
      }
      if (domainRegex.isEmpty && !readOnly) urls.lastOption.map{ url => centralConfig.update(RenormalizationCheckKey, url.id.get.id)}     // We assume id's are already sorted ( in getUrlList() )
    }

    db.readWrite{ implicit s =>
      val splitCases = potentialUriMigrations.map{ case (oldUri, newUris) =>
        val allInitUrls = originalRef(oldUri)
        val untouched = newUris.foldLeft(allInitUrls){case (all, newUri) => all -- migratedRef(newUri)}
        if (newUris.size == 1 && untouched.size == 0) {
          // we essentially have a uri migration here. Report a *APPLIED* uri migration event, so that other services like Eliza can sync.
          if (!readOnly) changedUriRepo.save(ChangedURI(oldUriId = oldUri, newUriId = newUris.head, state = ChangedURIStates.APPLIED))
          None
        } else {
          val splitted = newUris.foldLeft(Map(oldUri -> untouched)){case (m, newUri) => m + (newUri -> migratedRef(newUri))}
          Some(oldUri -> SplittedURIs(splitted))
        }
      }.flatten.toMap
      log.info(s"find ${splitCases.size} splitCases")
      sendSplitEmail(splitCases, readOnly)
    }

    changes = changes.sortBy(_._1.url)
    db.readWrite{ implicit s =>
      sendEmail(changes, readOnly)
    }
  }

}