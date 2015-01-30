package com.keepit.graph.manager

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.eliza.ElizaServiceClient
import com.keepit.abook.ABookServiceClient
import com.keepit.cortex.CortexServiceClient
import com.keepit.common.logging.Logging
import com.keepit.model.NormalizedURI
import com.keepit.common.core._
import com.keepit.common.db.SequenceNumber
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
        ldaCleanMsgSent = true
        Future.successful(Seq(LDAOldVersionCleanupGraphUpdate(ModelVersion[DenseLDA](2), 512)))
      }

      case NormalizedUriGraphUpdate => shoebox.getIndexableUris(seq.copy(), fetchSize).imap(_.map(NormalizedUriGraphUpdate.apply))

      case EmailAccountGraphUpdate => abook.getEmailAccountsChanged(seq.copy(), fetchSize).imap(_.map(EmailAccountGraphUpdate.apply))

      case EmailContactGraphUpdate => abook.getContactsChanged(seq.copy(), fetchSize).imap(_.map(EmailContactGraphUpdate.apply))

      case LibraryGraphUpdate => shoebox.getLibrariesChanged(seq.copy(), fetchSize).imap(_.map(LibraryGraphUpdate.apply))

      case LibraryMembershipGraphUpdate => shoebox.getLibraryMembershipsChanged(seq.copy(), fetchSize).imap(_.map(LibraryMembershipGraphUpdate.apply))
    }
  }
}
