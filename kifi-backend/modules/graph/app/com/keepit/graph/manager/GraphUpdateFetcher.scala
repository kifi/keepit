package com.keepit.graph.manager

import com.keepit.abook.model.EmailAccountInfo
import com.keepit.classify.{ DomainInfo, IngestableWithDomain, Domain }
import com.keepit.common.net.URI

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.eliza.ElizaServiceClient
import com.keepit.abook.ABookServiceClient
import com.keepit.cortex.CortexServiceClient
import com.keepit.common.logging.Logging
import com.keepit.model.{ IndexableUri, NormalizedURI }
import com.keepit.common.core._
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.cortex.core.ModelVersion

trait GraphUpdateFetcher {
  def fetch[U <: GraphUpdate](kind: GraphUpdateKind[U], seq: SequenceNumber[U], fetchSize: Int): Future[Seq[U]]
}

class GraphUpdateFetcherImpl @Inject() (
    shoebox: ShoeboxServiceClient,
    eliza: ElizaServiceClient,
    abook: ABookServiceClient,
    cortex: CortexServiceClient) extends GraphUpdateFetcher with Logging {

  private var ldaCleanMsgSent = false

  def fetch[U <: GraphUpdate](kind: GraphUpdateKind[U], seq: SequenceNumber[U], fetchSize: Int): Future[Seq[U]] = {

    log.info(s"Fetching up to $fetchSize $kind from sequence number $seq")

    kind match {

      case UserGraphUpdate => shoebox.getUserIndexable(seq.copy(), fetchSize).imap(_.map(UserGraphUpdate.apply))

      case SocialConnectionGraphUpdate => shoebox.getIndexableSocialConnections(seq.copy(), fetchSize).imap(_.map(SocialConnectionGraphUpdate.apply))

      case SocialUserInfoGraphUpdate => shoebox.getIndexableSocialUserInfos(seq.copy(), fetchSize).imap(_.map(SocialUserInfoGraphUpdate.apply))

      case UserConnectionGraphUpdate => shoebox.getUserConnectionsChanged(seq.copy(), fetchSize).imap(_.map(UserConnectionGraphUpdate.apply))

      case KeepGraphUpdate => shoebox.getBookmarksChanged(seq.copy(), fetchSize).imap(_.map(KeepGraphUpdate.apply))

      case SparseLDAGraphUpdate => {
        val cortexSeq = CortexSequenceNumber.fromLong[DenseLDA, NormalizedURI](seq.value)
        cortex.getSparseLDAFeaturesChanged(cortexSeq.modelVersion, cortexSeq.seq, fetchSize).imap {
          case (modelVersion, uriFeaturesBatch) =>
            uriFeaturesBatch.map { uriFeatures => SparseLDAGraphUpdate(modelVersion, uriFeatures) }
        }
      }

      case LDAOldVersionCleanupGraphUpdate => if (ldaCleanMsgSent) {
        Future.successful(Seq())
      } else {
        val update = LDAOldVersionCleanupGraphUpdate(ModelVersion[DenseLDA](2), 512)
        if (seq.value < update.modelVersion.version) {
          ldaCleanMsgSent = true
          Future.successful(Seq(update))
        } else {
          ldaCleanMsgSent = true
          Future.successful(Seq())
        }
      }

      case NormalizedUriGraphUpdate => {
        shoebox.getIndexableUris(seq.copy(), fetchSize).flatMap { indexableUris =>
          internDomainsByDomainNames(indexableUris).imap { domainInfos: Seq[DomainInfo] =>
            val domainIdOpts = indexableUris.map { uri =>
              domainInfos.find { domain =>
                uri.getDomainName == domain.hostname && !domain.isEmailProvider
              } match {
                case Some(domain: DomainInfo) => domain.id
                case None => None
              }
            }

            indexableUris.zip(domainIdOpts).map {
              case (indexableUri, domainIdOpt) =>
                NormalizedUriGraphUpdate.apply(indexableUri, domainIdOpt)
            }
          }
        }
      }

      case EmailAccountGraphUpdate => {
        abook.getEmailAccountsChanged(seq.copy(), fetchSize).flatMap { emailAccounts =>
          internDomainsByDomainNames(emailAccounts).imap { domainInfos: Seq[DomainInfo] =>
            val domainIdOpts = emailAccounts.map { email =>
              domainInfos.find { domain =>
                domain.hostname == email.getDomainName && !domain.isEmailProvider
              } match {
                case Some(domain: DomainInfo) => domain.id // domain was just interned, must have an id
                case None => None
              }
            }

            emailAccounts.zip(domainIdOpts).map {
              case (emailAccount, domainIdOpt) =>
                EmailAccountGraphUpdate.apply(emailAccount, domainIdOpt)
            }
          }
        }
      }

      case EmailContactGraphUpdate => abook.getContactsChanged(seq.copy(), fetchSize).imap(_.map(EmailContactGraphUpdate.apply))

      case LibraryGraphUpdate => shoebox.getLibrariesChanged(seq.copy(), fetchSize).imap(_.map(LibraryGraphUpdate.apply))

      case LibraryMembershipGraphUpdate => shoebox.getLibraryMembershipsChanged(seq.copy(), fetchSize).imap(_.map(LibraryMembershipGraphUpdate.apply))

      case OrganizationGraphUpdate => shoebox.getIngestableOrganizations(seq.copy(), fetchSize).imap(_.map(OrganizationGraphUpdate.apply))

      case OrganizationMembershipGraphUpdate => shoebox.getIngestableOrganizationMemberships(seq.copy(), fetchSize).imap(_.map(OrganizationMembershipGraphUpdate.apply))

      case UserIpAddressGraphUpdate => shoebox.getIngestableUserIpAddresses(seq.copy(), fetchSize).imap(_.map(UserIpAddressGraphUpdate.apply))
    }
  }

  protected def internDomainsByDomainNames[T <: IngestableWithDomain](ingestables: Seq[T]): Future[Seq[DomainInfo]] = {
    val domainNames = ingestables.groupBy[String] { _.getDomainName }.keys.toSeq
    shoebox.internDomainsByDomainNames(domainNames)
  }
}
